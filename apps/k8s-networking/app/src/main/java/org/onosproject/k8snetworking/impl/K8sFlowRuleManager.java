/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.k8snetworking.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snetworking.api.K8sFlowRuleService;
import org.onosproject.k8snetworking.api.K8sNetwork;
import org.onosproject.k8snetworking.api.K8sNetworkEvent;
import org.onosproject.k8snetworking.api.K8sNetworkListener;
import org.onosproject.k8snetworking.api.K8sNetworkService;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snetworking.api.Constants.ACL_TABLE;
import static org.onosproject.k8snetworking.api.Constants.ARP_TABLE;
import static org.onosproject.k8snetworking.api.Constants.FORWARDING_TABLE;
import static org.onosproject.k8snetworking.api.Constants.GROUPING_TABLE;
import static org.onosproject.k8snetworking.api.Constants.HOST_PREFIX;
import static org.onosproject.k8snetworking.api.Constants.JUMP_TABLE;
import static org.onosproject.k8snetworking.api.Constants.K8S_NETWORKING_APP_ID;
import static org.onosproject.k8snetworking.api.Constants.NAMESPACE_TABLE;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_CIDR_RULE;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_SNAT_RULE;
import static org.onosproject.k8snetworking.api.Constants.ROUTING_TABLE;
import static org.onosproject.k8snetworking.api.Constants.STAT_EGRESS_TABLE;
import static org.onosproject.k8snetworking.api.Constants.STAT_INGRESS_TABLE;
import static org.onosproject.k8snetworking.api.Constants.VTAG_TABLE;
import static org.onosproject.k8snetworking.api.Constants.VTAP_EGRESS_TABLE;
import static org.onosproject.k8snetworking.api.Constants.VTAP_INGRESS_TABLE;
import static org.onosproject.k8snetworking.api.Constants.INTG_INGRESS_TABLE;       // [MOD]
import static org.onosproject.k8snetworking.api.Constants.INTG_PORT_CLASSIFY_TABLE; // [MOD]
import static org.onosproject.k8snetworking.api.Constants.INTG_ARP_TABLE;           // [MOD]
import static org.onosproject.k8snetworking.api.Constants.INTG_SVC_FILTER;          // [MOD]
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.tunnelPortNumByNetId;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.buildExtension;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Sets flow rules directly using FlowRuleService.
 */
@Service
@Component(immediate = true)
public class K8sFlowRuleManager implements K8sFlowRuleService {

    private final Logger log = getLogger(getClass());

    private static final int DROP_PRIORITY = 0;
    private static final int HIGH_PRIORITY = 30000;
    private static final int TIMEOUT_SNAT_RULE = 60;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNetworkService k8sNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeService k8sNodeService;

    private final ExecutorService deviceEventExecutor =
            Executors.newSingleThreadExecutor(groupedThreads(
                    getClass().getSimpleName(), "device-event"));
    private final K8sNetworkListener internalNetworkListener = new InternalK8sNetworkListener();
    private final K8sNodeListener internalNodeListener = new InternalK8sNodeListener();

    private ApplicationId appId;
    private NodeId localNodeId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(K8S_NETWORKING_APP_ID);
        coreService.registerApplication(K8S_NETWORKING_APP_ID);
        k8sNodeService.addListener(internalNodeListener);
        k8sNetworkService.addListener(internalNetworkListener);
        localNodeId = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());
        k8sNodeService.completeNodes().forEach(this::initializePipeline);
        // Initialize exernal ovs node to connect flow tables
        k8sNodeService.nodes(K8sNode.Type.EXTOVS).forEach(this::initializeExtOvsPipeline);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sNodeService.removeListener(internalNodeListener);
        k8sNetworkService.removeListener(internalNetworkListener);
        leadershipService.withdraw(appId.name());
        deviceEventExecutor.shutdown();

        log.info("Stopped");
    }

    @Override
    public void setRule(ApplicationId appId, DeviceId deviceId,
                        TrafficSelector selector, TrafficTreatment treatment,
                        int priority, int tableType, boolean install) {
        FlowRule.Builder flowRuleBuilder = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(priority)
                .fromApp(appId)
                .forTable(tableType);

        if (priority == PRIORITY_SNAT_RULE) {
            flowRuleBuilder.makeTemporary(TIMEOUT_SNAT_RULE);
        } else {
            flowRuleBuilder.makePermanent();
        }

        applyRule(flowRuleBuilder.build(), install);
    }

    @Override
    public void setUpTableMissEntry(DeviceId deviceId, int table) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();

        treatment.drop();

        FlowRule flowRule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withSelector(selector.build())
                .withTreatment(treatment.build())
                .withPriority(DROP_PRIORITY)
                .fromApp(appId)
                .makePermanent()
                .forTable(table)
                .build();

        applyRule(flowRule, true);
    }

    @Override
    public void connectTables(DeviceId deviceId, int fromTable, int toTable) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();

        treatment.transition(toTable);

        FlowRule flowRule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withSelector(selector.build())
                .withTreatment(treatment.build())
                .withPriority(DROP_PRIORITY)
                .fromApp(appId)
                .makePermanent()
                .forTable(fromTable)
                .build();

        applyRule(flowRule, true);
    }

    private void applyRule(FlowRule flowRule, boolean install) {
        FlowRuleOperations.Builder flowOpsBuilder = FlowRuleOperations.builder();

        flowOpsBuilder = install ? flowOpsBuilder.add(flowRule) : flowOpsBuilder.remove(flowRule);

        flowRuleService.apply(flowOpsBuilder.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                log.debug("Provisioned vni or forwarding table");
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                log.debug("Failed to provision vni or forwarding table");
            }
        }));
    }

    protected void initializePipeline(K8sNode k8sNode) {

        DeviceId deviceId = k8sNode.intgBridge();

        // for inbound table transition
        // T0 -> T30
        connectTables(deviceId, STAT_INGRESS_TABLE, VTAG_TABLE);

        // for vTag and ARP table transition
        // T30 -> T35
        connectTables(deviceId, VTAG_TABLE, ARP_TABLE);

        // T41 -> T60
        connectTables(deviceId, INTG_SVC_FILTER, ROUTING_TABLE);

        // for jump and namespace table transition
        // T40 -> T49
        // connectTables(deviceId, JUMP_TABLE, NAMESPACE_TABLE);

        // for ARP and ACL table transition
        // T35 -> T49
        // connectTables(deviceId, ARP_TABLE, NAMESPACE_TABLE);

        // for namespace table transition to grouping table
        // T49 -> T50
        // connectTables(deviceId, NAMESPACE_TABLE, GROUPING_TABLE);

        // for grouping table transition to ACL table
        // T50 -> T55
        // connectTables(deviceId, GROUPING_TABLE, ACL_TABLE);

        // for ACL table transition to routing table
        // T55 -> T60
        // connectTables(deviceId, ACL_TABLE, ROUTING_TABLE);

        // for routing and outbound table transition
        // T60 -> T70
        // connectTables(deviceId, ROUTING_TABLE, STAT_EGRESS_TABLE);

        // for outbound table transition
        // T70 -> T71 -> T80
        // connectTables(deviceId, STAT_EGRESS_TABLE, VTAP_EGRESS_TABLE);
        // connectTables(deviceId, VTAP_EGRESS_TABLE, FORWARDING_TABLE);

        // [Mod] Simplifying this
        // T60 -> T80
        connectTables(deviceId, ROUTING_TABLE, FORWARDING_TABLE);
    }

    protected void initializeExtOvsPipeline(K8sNode node) {
        DeviceId deviceId = node.intgBridge();

        // T0 -> T30
        connectTables(deviceId, INTG_INGRESS_TABLE, INTG_PORT_CLASSIFY_TABLE);

        // T30 -> T35
        connectTables(deviceId, INTG_PORT_CLASSIFY_TABLE, INTG_ARP_TABLE);

        // T35 -> T50
        connectTables(deviceId, INTG_ARP_TABLE, GROUPING_TABLE);

        // T50 -> T60
        connectTables(deviceId, GROUPING_TABLE, ROUTING_TABLE);
    }

    // Flow OLD-60-2
    private void setAnyRoutingRule(IpPrefix srcIpPrefix, MacAddress mac,
                                   K8sNetwork k8sNetwork) {
        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(srcIpPrefix)
                .matchIPDst(IpPrefix.valueOf(k8sNetwork.cidr()));

        for (K8sNode node : k8sNodeService.completeNodes()) {
            TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder()
                .setTunnelId(Long.valueOf(k8sNetwork.segmentId()));

            if (node.hostname().equals(k8sNetwork.name())) {
                if (mac != null) {
                    tBuilder.setEthSrc(mac);
                }
                tBuilder.transition(STAT_EGRESS_TABLE);
            } else {
                PortNumber portNum = tunnelPortNumByNetId(k8sNetwork.networkId(),
                        k8sNetworkService, node);
                K8sNode localNode = k8sNodeService.node(k8sNetwork.name());

                tBuilder.extension(buildExtension(
                        deviceService,
                        node.intgBridge(),
                        localNode.dataIp().getIp4Address()),
                        node.intgBridge())
                        .setOutput(portNum);
            }

            FlowRule flowRule = DefaultFlowRule.builder()
                    .forDevice(node.intgBridge())
                    .withSelector(sBuilder.build())
                    .withTreatment(tBuilder.build())
                    .withPriority(PRIORITY_CIDR_RULE)
                    .fromApp(appId)
                    .makePermanent()
                    .forTable(ROUTING_TABLE)
                    .build();
            applyRule(flowRule, true);
        }
    }

    private void setupHostRoutingRule(K8sNetwork k8sNetwork) {
        // setAnyRoutingRule(IpPrefix.valueOf(
        //         k8sNetwork.gatewayIp(), HOST_PREFIX), null, k8sNetwork);
    }

    private class InternalK8sNodeListener implements K8sNodeListener {
        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNodeEvent event) {
            K8sNode k8sNode = event.subject();
            switch (event.type()) {
                case K8S_NODE_COMPLETE:
                    if(k8sNode.type() == K8sNode.Type.EXTOVS){
                        deviceEventExecutor.execute(() -> processExtOvsNodeCompletion(k8sNode));
                    } else {
                        deviceEventExecutor.execute(() -> processNodeCompletion(k8sNode));
                    }
                    break;
                case K8S_NODE_CREATED:
                default:
                    // do nothing
                    break;
            }
        }

        private void processNodeCompletion(K8sNode node) {
            log.info("COMPLETE node {} is detected", node.hostname());

            if (!isRelevantHelper()) {
                return;
            }

            initializePipeline(node);

            k8sNetworkService.networks().forEach(K8sFlowRuleManager.this::setupHostRoutingRule);
        }

        private void processExtOvsNodeCompletion(K8sNode node) {
            log.info("COMPLETE node {} is detected", node.hostname());

            if (!isRelevantHelper()) {
                return;
            }

            initializeExtOvsPipeline(node);
        }
    }

    private class InternalK8sNetworkListener implements K8sNetworkListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNetworkEvent event) {

            switch (event.type()) {
                case K8S_NETWORK_CREATED:
                    deviceEventExecutor.execute(() -> processNetworkCreation(event.subject()));
                    break;
                case K8S_NETWORK_REMOVED:
                default:
                    // do nothing
                    break;
            }
        }

        private void processNetworkCreation(K8sNetwork network) {
            if (!isRelevantHelper()) {
                return;
            }

            setupHostRoutingRule(network);
        }
    }
}
