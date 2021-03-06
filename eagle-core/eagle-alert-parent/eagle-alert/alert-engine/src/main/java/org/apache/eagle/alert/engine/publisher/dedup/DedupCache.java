/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.alert.engine.publisher.dedup;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.eagle.alert.engine.coordinator.StreamDefinition;
import org.apache.eagle.alert.engine.model.AlertStreamEvent;
import org.apache.eagle.alert.engine.publisher.impl.EventUniq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.typesafe.config.Config;

public class DedupCache {

    private static final Logger LOG = LoggerFactory.getLogger(DedupCache.class);

    private static final long CACHE_MAX_EXPIRE_TIME_IN_DAYS = 30;

    public static final String DEDUP_COUNT = "dedupCount";
    public static final String DOC_ID = "docId";
    public static final String DEDUP_FIRST_OCCURRENCE = "dedupFirstOccurrenceTime";

    private long lastUpdated = -1;
    private Map<EventUniq, ConcurrentLinkedDeque<DedupValue>> events = new ConcurrentHashMap<EventUniq, ConcurrentLinkedDeque<DedupValue>>();

    @SuppressWarnings("unused")
    private Config config;

    private String publishName;

    public DedupCache(Config config, String publishName) {
        this.config = config;
        this.publishName = publishName;
    }

    public Map<EventUniq, ConcurrentLinkedDeque<DedupValue>> getEvents() {
        if (lastUpdated < 0
            || System.currentTimeMillis() - lastUpdated > CACHE_MAX_EXPIRE_TIME_IN_DAYS * DateUtils.MILLIS_PER_DAY
            || events.size() <= 0) {
            lastUpdated = System.currentTimeMillis();
        }
        return events;
    }

    public boolean contains(EventUniq eventEniq) {
        return this.getEvents().containsKey(eventEniq);
    }

    public void removeEvent(EventUniq eventEniq) {
        if (this.contains(eventEniq)) {
            this.events.remove(eventEniq);
        }
    }

    public List<AlertStreamEvent> dedup(AlertStreamEvent event, EventUniq eventEniq,
                                        String dedupStateField, String stateFieldValue,
                                        String stateCloseValue) {
        DedupValue[] dedupValues = this.addOrUpdate(eventEniq, event, stateFieldValue, stateCloseValue);
        if (dedupValues != null) {
            // any of dedupValues won't be null
            if (dedupValues.length == 2) {
                // emit last event which includes count of dedup events & new state event
                return Arrays.asList(
                    this.mergeEventWithDedupValue(event, dedupValues[0], dedupStateField),
                    this.mergeEventWithDedupValue(event, dedupValues[1], dedupStateField));
            } else if (dedupValues.length == 1) {
                //populate firstOccurrenceTime & count
                return Arrays.asList(this.mergeEventWithDedupValue(event, dedupValues[0], dedupStateField));
            }
        }
        // duplicated, will be ignored
        return null;
    }

    public synchronized DedupValue[] addOrUpdate(EventUniq eventEniq, AlertStreamEvent event, String stateFieldValue, String stateCloseValue) {
        Map<EventUniq, ConcurrentLinkedDeque<DedupValue>> events = this.getEvents();
        if (!events.containsKey(eventEniq)
            || (events.containsKey(eventEniq)
            && events.get(eventEniq).size() > 0
            && !StringUtils.equalsIgnoreCase(stateFieldValue,
            events.get(eventEniq).getLast().getStateFieldValue()))) {
            DedupValue[] dedupValues = this.add(eventEniq, event, stateFieldValue, stateCloseValue);
            return dedupValues;
        } else {
            // update count
            this.updateCount(eventEniq);
            return null;
        }
    }

    private DedupValue[] add(EventUniq eventEniq, AlertStreamEvent event, String stateFieldValue, String stateCloseValue) {
        DedupValue dedupValue = null;
        if (!events.containsKey(eventEniq)) {
            dedupValue = createDedupValue(eventEniq, event, stateFieldValue);
            ConcurrentLinkedDeque<DedupValue> dedupValues = new ConcurrentLinkedDeque<>();
            dedupValues.add(dedupValue);
            // skip the event which put failed due to concurrency
            events.put(eventEniq, dedupValues);
            LOG.info("{} Add new dedup key {}, and value {}", this.publishName, eventEniq, dedupValues);
        } else if (!StringUtils.equalsIgnoreCase(stateFieldValue,
            events.get(eventEniq).getLast().getStateFieldValue())) {
            // existing a de-dup value, try update or reset
            DedupValue lastDedupValue = events.get(eventEniq).getLast();
            dedupValue = updateDedupValue(lastDedupValue, eventEniq, event, stateFieldValue, stateCloseValue);
            LOG.info("{} Update dedup key {}, and value {}", this.publishName, eventEniq, dedupValue);
        }
        if (dedupValue == null) {
            return null;
        }
        return new DedupValue[] {dedupValue};
    }

    private DedupValue updateDedupValue(DedupValue lastDedupValue, EventUniq eventEniq, AlertStreamEvent event, String stateFieldValue, String stateCloseValue) {
        if (lastDedupValue.getFirstOccurrence() >= eventEniq.timestamp) {
            // if dedup value happens later then event, dedup state changes.
            return null;
        }

        if (lastDedupValue.getStateFieldValue().equals(stateCloseValue)
            && eventEniq.timestamp < lastDedupValue.getCloseTime()) {
            DedupValue dv = createDedupValue(eventEniq, event, stateFieldValue);
            lastDedupValue.resetTo(dv);
        } else {
            // update lastDedupValue, set closeTime when close
            lastDedupValue.setStateFieldValue(stateFieldValue);
            if (stateFieldValue.equals(stateCloseValue)) {
                lastDedupValue.setCloseTime(eventEniq.timestamp); // when close an event, set closeTime for further check
            }
        }
        return lastDedupValue;
    }

    private DedupValue createDedupValue(EventUniq eventEniq, AlertStreamEvent event, String stateFieldValue) {
        DedupValue dedupValue;
        dedupValue = new DedupValue();
        dedupValue.setFirstOccurrence(eventEniq.timestamp);
        int idx = event.getSchema().getColumnIndex(DOC_ID);
        if (idx >= 0) {
            dedupValue.setDocId(event.getData()[idx].toString());
        } else {
            dedupValue.setDocId("");
        }
        dedupValue.setCount(1);
        dedupValue.setCloseTime(0);
        dedupValue.setStateFieldValue(stateFieldValue);
        return dedupValue;
    }

    private DedupValue updateCount(EventUniq eventEniq) {
        ConcurrentLinkedDeque<DedupValue> dedupValues = events.get(eventEniq);
        if (dedupValues == null || dedupValues.size() <= 0) {
            LOG.warn("{} No dedup values found for {}, cannot update count", this.publishName, eventEniq);
            return null;
        } else {
            DedupValue dedupValue = dedupValues.getLast();
            dedupValue.setCount(dedupValue.getCount() + 1);
            String updateMsg = String.format(
                "%s Update count for dedup key %s, value %s and count %s", this.publishName, eventEniq,
                dedupValue.getStateFieldValue(), dedupValue.getCount());
            if (LOG.isDebugEnabled()) {
                LOG.debug(updateMsg);
            }
            return dedupValue;
        }
    }

    private AlertStreamEvent mergeEventWithDedupValue(AlertStreamEvent originalEvent,
                                                      DedupValue dedupValue, String dedupStateField) {
        AlertStreamEvent event = new AlertStreamEvent();
        Object[] newdata = new Object[originalEvent.getData().length];
        for (int i = 0; i < originalEvent.getData().length; i++) {
            newdata[i] = originalEvent.getData()[i];
        }
        event.setData(newdata);
        event.setStreamId(originalEvent.getStreamId());
        event.setSchema(originalEvent.getSchema());
        event.setPolicyId(originalEvent.getPolicyId());
        event.setCreatedTime(originalEvent.getCreatedTime());
        event.setCreatedBy(originalEvent.getCreatedBy());
        event.setTimestamp(originalEvent.getTimestamp());
        StreamDefinition streamDefinition = event.getSchema();
        for (int i = 0; i < event.getData().length; i++) {
            String colName = streamDefinition.getColumns().get(i).getName();
            if (Objects.equal(colName, dedupStateField)) {
                event.getData()[i] = dedupValue.getStateFieldValue();
            }
            if (Objects.equal(colName, DEDUP_COUNT)) {
                event.getData()[i] = dedupValue.getCount();
            }
            if (Objects.equal(colName, DEDUP_FIRST_OCCURRENCE)) {
                event.getData()[i] = dedupValue.getFirstOccurrence();
            }
            if (Objects.equal(colName, DOC_ID)) {
                event.getData()[i] = dedupValue.getDocId();
            }
        }
        return event;
    }

}
