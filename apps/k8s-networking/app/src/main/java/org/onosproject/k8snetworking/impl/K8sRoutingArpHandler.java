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
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snetworking.api.K8sFlowRuleService;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeAdminService;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snetworking.api.Constants.EXT_ENTRY_TABLE;
import static org.onosproject.k8snetworking.api.Constants.K8S_NETWORKING_APP_ID;
import static org.onosproject.k8snetworking.api.Constants.NODE_IP_PREFIX;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_ARP_POD_RULE;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_ARP_REPLY_RULE;
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.getCclassIpPrefixFromCidr;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Handles ARP request/reply from external gateway.
 */
@Component(immediate = true)
public class K8sRoutingArpHandler {
    private final Logger log = getLogger(getClass());

    private static final long SLEEP_MS = 5000;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeAdminService k8sNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sFlowRuleService k8sFlowRuleService;

    private ApplicationId appId;
    private NodeId localNodeId;

    private final InternalK8sNodeListener k8sNodeListener = new InternalK8sNodeListener();
    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final PacketProcessor packetProcessor = new InternalPacketProcessor();


    @Activate
    protected void activate() {
        appId = coreService.registerApplication(K8S_NETWORKING_APP_ID);
        localNodeId = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());
        k8sNodeService.addListener(k8sNodeListener);
        packetService.addProcessor(packetProcessor, PacketProcessor.director(1));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sNodeService.removeListener(k8sNodeListener);
        packetService.removeProcessor(packetProcessor);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();
        log.info("Stopped");
    }

    private void processArpPacket(PacketContext context, Ethernet ethernet) {
        ARP arp = (ARP) ethernet.getPayload();

        if (arp.getOpCode() == ARP.OP_REPLY) {
            IpAddress spa = Ip4Address.valueOf(arp.getSenderProtocolAddress());
            MacAddress sha = MacAddress.valueOf(arp.getSenderHardwareAddress());

            log.info("ARP reply from ip: {}, mac: {}", spa, sha);

            Set<IpAddress> gatewayIps = k8sNodeService.completeNodes().stream()
                    .map(K8sNode::extGatewayIp).collect(Collectors.toSet());

            if (!gatewayIps.contains(spa)) {
                return;
            }

            log.info("ARP reply from external gateway ip: {}, mac: {}", spa, sha);

            k8sNodeService.completeNodes().stream()
                    .filter(n -> n.extGatewayMac() == null)
                    .forEach(n -> {
                        K8sNode updated = n.updateExtGatewayMac(sha);
                        k8sNodeService.updateNode(updated);
                    });
        }
        else if (arp.getOpCode() == ARP.OP_REQUEST) {
            IpAddress spa = Ip4Address.valueOf(arp.getSenderProtocolAddress());
            MacAddress sha = MacAddress.valueOf(arp.getSenderHardwareAddress());
            IpAddress tpa = Ip4Address.valueOf(arp.getTargetProtocolAddress());

            if (tpa.toString().startsWith(NODE_IP_PREFIX)) {
                log.info("Who has {} ? Tell Pod {}({})", tpa, spa, sha);
                String targetIpPostfix = tpa.toString().split("\\.", 3)[2];
                String senderPodCidr = getCclassIpPrefixFromCidr(spa.toString())  + ".0";

                k8sNodeService.completeNodes().forEach(n -> {
                    String extGatewayIpPostfix = n.extGatewayIp().toString().split("\\.", 3)[2];
                    String podCidr = n.podCidr().split("\\/")[0];

                    if (targetIpPostfix.equals(extGatewayIpPostfix) && senderPodCidr.equals(podCidr)) {
                        MacAddress replyMac = n.extGatewayMac();

                        Ethernet ethReply = ARP.buildArpReply(
                            tpa.getIp4Address(),
                            replyMac,
                            ethernet);

                        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setOutput(context.inPacket().receivedFrom().port())
                        .build();

                        packetService.emit(new DefaultOutboundPacket(
                            context.inPacket().receivedFrom().deviceId(),
                            treatment,
                            ByteBuffer.wrap(ethReply.serialize())));

                        log.info("{} is at {}", tpa, replyMac.toString());
                    }
                });
            }
        }
    }

    private void sendArpRequest(K8sNode k8sNode) {
        MacAddress bridgeMac = k8sNode.extBridgeMac();
        IpAddress bridgeIp = k8sNode.extBridgeIp();
        IpAddress extGatewayIp = k8sNode.extGatewayIp();
        Ethernet ethRequest = ARP.buildArpRequest(bridgeMac.toBytes(), bridgeIp.toOctets(),
                extGatewayIp.toOctets(), VlanId.NO_VID);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(k8sNode.extBridgePortNum())
                .build();

        packetService.emit(new DefaultOutboundPacket(
                k8sNode.extBridge(),
                treatment,
                ByteBuffer.wrap(ethRequest.serialize())));
    }

    private void setArpReplyRule(K8sNode extOvs, boolean install) {

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP)
                .matchArpOp(ARP.OP_REPLY)
                .matchArpSpa(Ip4Address.valueOf(extOvs.extGatewayIp().toString()))
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .punt()
                .build();

        k8sFlowRuleService.setRule(
                appId,
                extOvs.extBridge(),
                selector,
                treatment,
                PRIORITY_ARP_REPLY_RULE,
                EXT_ENTRY_TABLE,
                install
        );
    }

    private void setPodArpRequestRule(K8sNode extOvs, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchInPort(extOvs.extToIntgPatchPortNum())
                .matchEthType(Ethernet.TYPE_ARP)
                .matchArpOp(ARP.OP_REQUEST)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(extOvs.extBridgePortNum())
                .build();

        k8sFlowRuleService.setRule(
                appId,
                extOvs.extBridge(),
                selector,
                treatment,
                PRIORITY_ARP_POD_RULE,
                EXT_ENTRY_TABLE,
                install
        );
    }

    private void setPodArpReplyRule(K8sNode extOvs, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchInPort(extOvs.extBridgePortNum())
                .matchEthType(Ethernet.TYPE_ARP)
                .matchArpOp(ARP.OP_REPLY)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(extOvs.extToIntgPatchPortNum())
                .build();

        k8sFlowRuleService.setRule(
                appId,
                extOvs.extBridge(),
                selector,
                treatment,
                PRIORITY_ARP_POD_RULE,
                EXT_ENTRY_TABLE,
                install
        );
    }

    private class InternalK8sNodeListener implements K8sNodeListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNodeEvent event) {
            K8sNode node = event.subject();
            switch (event.type()) {
                case K8S_NODE_COMPLETE:
                    if(node.type() == K8sNode.Type.EXTOVS){
                        // TODO: See if we need to do something here
                        eventExecutor.execute(() -> processNodeCompletion(node));
                    } else {
                        // Do Nothing
                    }
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

            setArpReplyRule(node, true);
            setPodArpRequestRule(node, true);
            setPodArpReplyRule(node, true);

            try {
                sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                log.error("Exception caused during ARP requesting...");
            }

            sendArpRequest(node);
        }
    }

    private class InternalPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethernet = pkt.parsed();
            if (ethernet != null && ethernet.getEtherType() == Ethernet.TYPE_ARP) {
                eventExecutor.execute(() -> processArpPacket(context, ethernet));
            }
        }
    }
}
