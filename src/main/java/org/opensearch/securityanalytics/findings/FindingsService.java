/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.findings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.commons.alerting.AlertingPluginInterface;
import org.opensearch.commons.alerting.model.FindingWithDocs;
import org.opensearch.commons.alerting.model.Table;
import org.opensearch.rest.RestStatus;
import org.opensearch.securityanalytics.action.FindingDto;
import org.opensearch.securityanalytics.action.GetDetectorAction;
import org.opensearch.securityanalytics.action.GetDetectorRequest;
import org.opensearch.securityanalytics.action.GetDetectorResponse;
import org.opensearch.securityanalytics.action.GetFindingsResponse;
import org.opensearch.securityanalytics.model.Detector;
import org.opensearch.securityanalytics.util.SecurityAnalyticsException;

/**
 * Implements searching/fetching of findings
 */
public class FindingsService {

    private Client client;

    private static final Logger log = LogManager.getLogger(FindingsService.class);


    public FindingsService() {}

    public FindingsService(Client client) {
        this.client = client;
    }

    /**
     * Searches findings generated by specific Detector
     * @param detectorId id of Detector
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getFindingsByDetectorId(String detectorId, Table table, ActionListener<GetFindingsResponse> listener) {
        this.client.execute(GetDetectorAction.INSTANCE, new GetDetectorRequest(detectorId, -3L), new ActionListener<>() {

            @Override
            public void onResponse(GetDetectorResponse getDetectorResponse) {
                // Get all monitor ids from detector
                List<String> monitorIds = getDetectorResponse.getDetector().getMonitorIds();
                // Using GroupedActionListener here as we're going to issue one GetFindingsActions for each monitorId
                ActionListener<GetFindingsResponse> multiGetFindingsListener = new GroupedActionListener<>(new ActionListener<>() {
                    @Override
                    public void onResponse(Collection<GetFindingsResponse> responses) {
                        Integer totalFindings = 0;
                        List<FindingDto> findings = new ArrayList<>();
                        // Merge all findings into one response
                        for(GetFindingsResponse resp : responses) {
                            totalFindings += resp.getTotalFindings();
                            findings.addAll(resp.getFindings());
                        }
                        GetFindingsResponse masterResponse = new GetFindingsResponse(
                                totalFindings,
                                findings
                        );
                        // Send master response back
                        listener.onResponse(masterResponse);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        log.error("Failed to fetch findings for detector " + detectorId, e);
                        listener.onFailure(SecurityAnalyticsException.wrap(e));
                    }
                }, monitorIds.size());
                // Execute GetFindingsAction for each monitor
                for(String monitorId : monitorIds) {
                    FindingsService.this.getFindingsByMonitorId(null, monitorId, table, multiGetFindingsListener);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(SecurityAnalyticsException.wrap(e));
            }
        });
    }

    /**
     * Searches findings generated by specific Monitor
     * @param monitorToDetectorMapping monitorId --&gt; detectorId mapper
     * @param monitorId id of Monitor
     * @param table group of search related parameters
     * @param listener ActionListener to get notified on response or error
     */
    public void getFindingsByMonitorId(
            Map<String, String> monitorToDetectorMapping,
            String monitorId,
            Table table,
            ActionListener<GetFindingsResponse> listener
    ) {

        org.opensearch.commons.alerting.action.GetFindingsRequest req =
                new org.opensearch.commons.alerting.action.GetFindingsRequest(
                null,
                table,
                monitorId,
                null,
                null
        );

        AlertingPluginInterface.INSTANCE.getFindings((NodeClient) client, req, new ActionListener<>() {
                    @Override
                    public void onResponse(
                            org.opensearch.commons.alerting.action.GetFindingsResponse getFindingsResponse
                    ) {
                        // Convert response to SA's GetFindingsResponse
                        listener.onResponse(new GetFindingsResponse(
                                getFindingsResponse.getTotalFindings(),
                                getFindingsResponse.getFindings()
                                        .stream().map(e -> mapFindingWithDocsToFindingDto(
                                                e,
                                                monitorToDetectorMapping.get(e.getFinding().getMonitorId())
                                        )).collect(Collectors.toList())
                        ));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                }
        );

     }

    void setIndicesAdminClient(Client client) {
        this.client = client;
    }

    public void getFindings(
            List<Detector> detectors,
            Table table,
            ActionListener<GetFindingsResponse> listener
    ) {
        if (detectors.size() == 0) {
            throw SecurityAnalyticsException.wrap(new IllegalArgumentException("detector list is empty!"));
        }

        List<String> monitorIds = new ArrayList<>();
        Map<String, String> monitorToDetectorMapping = new HashMap<>();
        detectors.forEach(detector -> {
            monitorIds.addAll(detector.getMonitorIds());
            monitorIds.forEach(monitorId -> monitorToDetectorMapping.put(monitorId, detector.getId()));

        });
        // Using GroupedActionListener here as we're going to issue one GetFindingsActions for each monitorId
        ActionListener<GetFindingsResponse> multiGetFindingsListener = new GroupedActionListener<>(new ActionListener<>() {
            @Override
            public void onResponse(Collection<GetFindingsResponse> responses) {
                Integer totalFindings = 0;
                List<FindingDto> findings = new ArrayList<>();
                // Merge all findings into one response
                for(GetFindingsResponse resp : responses) {
                    totalFindings += resp.getTotalFindings();
                    findings.addAll(resp.getFindings());
                }
                GetFindingsResponse masterResponse = new GetFindingsResponse(
                        totalFindings,
                        findings
                );
                // Send master response back
                listener.onResponse(masterResponse);
            }

            @Override
            public void onFailure(Exception e) {
                log.error("Failed to fetch alerts for detectors: [" +
                        detectors.stream().map(d -> d.getId()).collect(Collectors.joining(",")) + "]", e);
                listener.onFailure(SecurityAnalyticsException.wrap(e));
            }
        }, monitorIds.size());
        // Execute GetFindingsAction for each monitor
        for (String monitorId : monitorIds) {
            FindingsService.this.getFindingsByMonitorId(monitorToDetectorMapping, monitorId, table, multiGetFindingsListener);
        }
    }

    public FindingDto mapFindingWithDocsToFindingDto(FindingWithDocs findingWithDocs, String detectorId) {
        return new FindingDto(
                detectorId,
                findingWithDocs.getFinding().getId(),
                findingWithDocs.getFinding().getRelatedDocIds(),
                findingWithDocs.getFinding().getIndex(),
                findingWithDocs.getFinding().getDocLevelQueries(),
                findingWithDocs.getFinding().getTimestamp(),
                findingWithDocs.getDocuments()
        );
    }
}