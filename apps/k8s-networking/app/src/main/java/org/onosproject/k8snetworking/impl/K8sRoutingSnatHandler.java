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
import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.TpPort;
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
import org.onosproject.k8snetworking.api.K8sPort;
import org.onosproject.k8snetworking.util.RulePopulatorUtil;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.instructions.ExtensionTreatment;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snetworking.api.Constants.DEFAULT_GATEWAY_MAC;
import static org.onosproject.k8snetworking.api.Constants.EXT_ENTRY_TABLE;
import static org.onosproject.k8snetworking.api.Constants.K8S_NETWORKING_APP_ID;
import static org.onosproject.k8snetworking.api.Constants.POD_RESOLUTION_TABLE;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_EXTERNAL_ROUTING_RULE;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_STATEFUL_SNAT_RULE;
import static org.onosproject.k8snetworking.api.Constants.ROUTING_TABLE;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.CT_NAT_SRC_FLAG;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.buildMoveArpShaToThaExtension;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.buildMoveArpSpaToTpaExtension;
import static org.onosproject.k8snetworking.util.RulePopulatorUtil.buildMoveEthSrcToDstExtension;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provides POD's internal to external connectivity using source NAT (SNAT).
 */
@Component(immediate = true)
public class K8sRoutingSnatHandler {

    private final Logger log = getLogger(getClass());

    private static final int HOST_PREFIX = 32;

    // we try to avoid port number overlapping with node port (30000 ~ 32767)
    // in case the user has customized node port range, the following static
    // value should be changed accordingly
    private static final int TP_PORT_MINIMUM_NUM = 32768;
    private static final int TP_PORT_MAXIMUM_NUM = 65535;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DriverService driverService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNetworkService k8sNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeService k8sNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sFlowRuleService k8sFlowRuleService;

    private final InternalK8sNetworkListener k8sNetworkListener =
            new InternalK8sNetworkListener();
    private final InternalK8sNodeListener k8sNodeListener =
            new InternalK8sNodeListener();
    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private ApplicationId appId;
    private NodeId localNodeId;


    @Activate
    protected void activate() {
        appId = coreService.registerApplication(K8S_NETWORKING_APP_ID);

        localNodeId = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());
        k8sNetworkService.addListener(k8sNetworkListener);
        k8sNodeService.addListener(k8sNodeListener);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sNodeService.removeListener(k8sNodeListener);
        k8sNetworkService.removeListener(k8sNetworkListener);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    private void setContainerToExtRule(K8sNode k8sNode, boolean install) {

        K8sNetwork net = k8sNetworkService.network(k8sNode.hostname());

        if (net == null) {
            return;
        }

        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchTunnelId(Long.valueOf(net.segmentId()))
                .matchEthDst(DEFAULT_GATEWAY_MAC);

        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder()
                .setOutput(k8sNode.intgToExtPatchPortNum());

        k8sFlowRuleService.setRule(
                appId,
                k8sNode.intgBridge(),
                sBuilder.build(),
                tBuilder.build(),
                PRIORITY_EXTERNAL_ROUTING_RULE,
                ROUTING_TABLE,
                install);
    }

    private void setExtToContainerRule(K8sNode k8sNode,
                                       K8sPort k8sPort, boolean install) {

        K8sNetwork net = k8sNetworkService.network(k8sPort.networkId());

        if (net == null) {
            return;
        }

        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf(k8sPort.ipAddress(), HOST_PREFIX));

        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder()
                .setOutput(k8sNode.extToIntgPatchPortNum());

        k8sFlowRuleService.setRule(
                appId,
                k8sNode.extBridge(),
                sBuilder.build(),
                tBuilder.build(),
                PRIORITY_STATEFUL_SNAT_RULE,
                POD_RESOLUTION_TABLE,
                install);
    }

    private void setSnatDownstreamRule(K8sNode k8sNode,
                                       boolean install) {
        K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findFirst().get();
        DeviceId deviceId = extOvs.extBridge();

        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf(extOvs.extBridgeIp(), HOST_PREFIX));

        ExtensionTreatment natTreatment = RulePopulatorUtil
                .niciraConnTrackTreatmentBuilder(driverService, deviceId)
                .commit(false)
                .natAction(true)
                .table((short) POD_RESOLUTION_TABLE)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setEthSrc(DEFAULT_GATEWAY_MAC)
                .extension(natTreatment, deviceId)
                .build();

        k8sFlowRuleService.setRule(
                appId,
                deviceId,
                sBuilder.build(),
                treatment,
                PRIORITY_STATEFUL_SNAT_RULE,
                EXT_ENTRY_TABLE,
                install);
    }

    // EXTOVS kbr-ex 0-3
    private void setSnatUpstreamRule(K8sNode k8sNode,
                                     boolean install) {
        K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findFirst().get();
        K8sNetwork net = k8sNetworkService.network(k8sNode.hostname());

        if (net == null) {
            return;
        }

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchInPort(extOvs.extToIntgPatchPortNum())
                .matchEthDst(DEFAULT_GATEWAY_MAC)
                .build();

        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        if (install) {
            ExtensionTreatment natTreatment = RulePopulatorUtil
                    .niciraConnTrackTreatmentBuilder(driverService, extOvs.extBridge())
                    .commit(true)
                    .natFlag(CT_NAT_SRC_FLAG)
                    .natAction(true)
                    .natIp(extOvs.extBridgeIp())
                    .natPortMin(TpPort.tpPort(TP_PORT_MINIMUM_NUM))
                    .natPortMax(TpPort.tpPort(TP_PORT_MAXIMUM_NUM))
                    .build();

            tBuilder.extension(natTreatment, extOvs.extBridge())
                    .setEthSrc(extOvs.extBridgeMac())
                    .setEthDst(k8sNode.extGatewayMac())
                    .setOutput(extOvs.extBridgePortNum());
        }

        k8sFlowRuleService.setRule(
                appId,
                extOvs.extBridge(),
                selector,
                tBuilder.build(),
                PRIORITY_STATEFUL_SNAT_RULE,
                EXT_ENTRY_TABLE,
                install);
    }

    private void setExtIntfArpRule(K8sNode k8sNode, boolean install) {
            K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findFirst().get();
        // k8sNodeService.completeNodes().forEach(n -> {
            Device device = deviceService.getDevice(extOvs.extBridge());
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_ARP)
                    .matchArpOp(ARP.OP_REQUEST)
                    .matchArpTpa(Ip4Address.valueOf(extOvs.extBridgeIp().toString()))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setArpOp(ARP.OP_REPLY)
                    .extension(buildMoveEthSrcToDstExtension(device), device.id())
                    .extension(buildMoveArpShaToThaExtension(device), device.id())
                    .extension(buildMoveArpSpaToTpaExtension(device), device.id())
                    .setEthSrc(extOvs.extBridgeMac())
                    .setArpSha(extOvs.extBridgeMac())
                    .setArpSpa(Ip4Address.valueOf(extOvs.extBridgeIp().toString()))
                    .setOutput(PortNumber.IN_PORT)
                    .build();

            k8sFlowRuleService.setRule(
                    appId,
                    extOvs.extBridge(),
                    selector,
                    treatment,
                    PRIORITY_STATEFUL_SNAT_RULE,
                    EXT_ENTRY_TABLE,
                    install);
        // });
    }

    // Flow EXTOVS 60-2
    private void setExtOvsRoutingRules(K8sNode k8sNode){
        K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findAny().get();

        String dataIpStr = k8sNode.dataIp().toString();
        String interfaceName = "eth" + dataIpStr.split("\\.")[3];

        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .matchIPDst(IpPrefix.valueOf(k8sNode.podCidr()))
            // .matchEthDst(extOvs.intgBridgeMac())
            .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setEthSrc(extOvs.intgBridgeMac())
            .setEthDst(k8sNode.intgBridgeMac())
            .setOutput(extOvs.customIntgPortNum(interfaceName))
            .build();

        k8sFlowRuleService.setRule(
            appId,
            extOvs.intgBridge(),
            selector,
            treatment,
            PRIORITY_EXTERNAL_ROUTING_RULE,
            ROUTING_TABLE,
            true);
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
                    eventExecutor.execute(() -> processNodeCompletion(k8sNode));
                    break;
                case K8S_NODE_UPDATED:
                    eventExecutor.execute(() -> processNodeUpdate(k8sNode));
                    break;
                case K8S_NODE_INCOMPLETE:
                default:
                    break;
            }
        }

        private void processNodeCompletion(K8sNode node) {
            if (!isRelevantHelper()) {
                return;
            }
            if (node.type().equals(K8sNode.Type.EXTOVS)) {
                setExtIntfArpRule(node, true);
            }
            // setSnatDownstreamRule(node, true);
            // setContainerToExtRule(node, true);
            setExtOvsRoutingRules(node);
        }

        private void processNodeUpdate(K8sNode k8sNode) {
            if (k8sNode.extGatewayMac() != null) {
                setSnatUpstreamRule(k8sNode, true);
            }
        }
    }

    private class InternalK8sNetworkListener implements K8sNetworkListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNetworkEvent event) {
            switch (event.type()) {
                case K8S_PORT_ACTIVATED:
                    // eventExecutor.execute(() -> processPortActivation(event.port()));
                    break;
                case K8S_PORT_REMOVED:
                    // eventExecutor.execute(() -> processPortRemoval(event.port()));
                    break;
                default:
                    break;
            }
        }

        // private void processPortActivation(K8sPort port) {
        //     if (!isRelevantHelper()) {
        //         return;
        //     }

        //     k8sNodeService.completeNodes().forEach(n ->
        //             setExtToContainerRule(n, port, true));
        // }

        // private void processPortRemoval(K8sPort port) {
        //     if (!isRelevantHelper()) {
        //         return;
        //     }

        //     k8sNodeService.completeNodes().forEach(n ->
        //             setExtToContainerRule(n, port, false));
        // }
    }
}
