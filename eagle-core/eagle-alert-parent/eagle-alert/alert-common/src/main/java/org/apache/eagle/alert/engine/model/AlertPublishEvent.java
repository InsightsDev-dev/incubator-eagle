/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *  <p/>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p/>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.eagle.alert.engine.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AlertPublishEvent {
    private String alertId;
    private String siteId;
    private List<String> appIds;
    private String policyId;
    private String policyValue;
    private long alertTimestamp;
    private Map<String, String> alertData;

    public static final String SITE_ID_KEY = "siteId";
    public static final String APP_IDS_KEY = "appIds";
    public static final String POLICY_VALUE_KEY = "policyValue";

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public List<String> getAppIds() {
        return appIds;
    }

    public void setAppIds(List<String> appIds) {
        this.appIds = appIds;
    }

    public String getPolicyValue() {
        return policyValue;
    }

    public void setPolicyValue(String policyValue) {
        this.policyValue = policyValue;
    }

    public long getAlertTimestamp() {
        return alertTimestamp;
    }

    public void setAlertTimestamp(long alertTimestamp) {
        this.alertTimestamp = alertTimestamp;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }


    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public Map<String, String> getAlertData() {
        return alertData;
    }

    public void setAlertData(Map<String, String> alertData) {
        this.alertData = alertData;
    }

    public static AlertPublishEvent createAlertPublishEvent(AlertStreamEvent event) {
        AlertPublishEvent alertEvent = new AlertPublishEvent();
        alertEvent.setAlertId(UUID.randomUUID().toString());
        alertEvent.setPolicyId(event.getPolicyId());
        alertEvent.setAlertTimestamp(event.getCreatedTime());
        if (event.getExtraData() != null && !event.getExtraData().isEmpty()) {
            alertEvent.setSiteId(event.getExtraData().get(SITE_ID_KEY).toString());
            alertEvent.setPolicyValue(event.getExtraData().get(POLICY_VALUE_KEY).toString());
            alertEvent.setAppIds((List<String>) event.getExtraData().get(APP_IDS_KEY));
        }
        alertEvent.setAlertData(event.getDataMap());
        return alertEvent;
    }

}

