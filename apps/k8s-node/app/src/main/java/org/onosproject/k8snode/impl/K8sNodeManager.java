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
package org.onosproject.k8snode.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.Tools;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNode.Type;
import org.onosproject.k8snode.api.K8sNodeAdminService;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.k8snode.api.K8sNodeStore;
import org.onosproject.k8snode.api.K8sNodeStoreDelegate;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.store.service.AtomicCounter;
import org.onosproject.store.service.StorageService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snode.api.K8sNode.Type.EXTOVS;
import static org.onosproject.k8snode.api.K8sNodeState.COMPLETE;
import static org.onosproject.k8snode.util.K8sNodeUtil.genDpid;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service administering the inventory of kubernetes nodes.
 */
@Service
@Component(immediate = true)
public class K8sNodeManager
        extends ListenerRegistry<K8sNodeEvent, K8sNodeListener>
        implements K8sNodeService, K8sNodeAdminService {

    private final Logger log = getLogger(getClass());

    private static final String MSG_NODE = "Kubernetes node %s %s";
    private static final String MSG_CREATED = "created";
    private static final String MSG_UPDATED = "updated";
    private static final String MSG_REMOVED = "removed";
    private static final String OVSDB_PORT = "ovsdbPortNum";
    private static final int DEFAULT_OVSDB_PORT = 6640;

    private static final String ERR_NULL_NODE = "Kubernetes node cannot be null";
    private static final String ERR_NULL_HOSTNAME = "Kubernetes node hostname cannot be null";
    private static final String ERR_NULL_DEVICE_ID = "Kubernetes node device ID cannot be null";

    private static final String DEVICE_ID_COUNTER_NAME = "device-id-counter";
    private static final String NOT_DUPLICATED_MSG = "% cannot be duplicated";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeStore nodeStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Property(name = OVSDB_PORT, intValue = DEFAULT_OVSDB_PORT,
            label = "OVSDB server listen port")
    private int ovsdbPort = DEFAULT_OVSDB_PORT;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final K8sNodeStoreDelegate delegate = new K8sNodeManager.InternalNodeStoreDelegate();

    private AtomicCounter deviceIdCounter;

    private ApplicationId appId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP_ID);
        nodeStore.setDelegate(delegate);

        leadershipService.runForLeadership(appId.name());

        deviceIdCounter = storageService.getAtomicCounter(DEVICE_ID_COUNTER_NAME);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        nodeStore.unsetDelegate(delegate);

        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        int updatedOvsdbPort = Tools.getIntegerProperty(properties, OVSDB_PORT);
        if (!Objects.equals(updatedOvsdbPort, ovsdbPort)) {
            ovsdbPort = updatedOvsdbPort;
        }

        log.info("Modified");
    }

    // Creation of k8s node
    @Override
    public void createNode(K8sNode node) {
        checkNotNull(node, ERR_NULL_NODE);

        K8sNode intNode;
        // K8sNode extNode;
        K8sNode localNode;

        if (node.intgBridge() == null) {
            String deviceIdStr = genDpid(deviceIdCounter.incrementAndGet());
            checkNotNull(deviceIdStr, ERR_NULL_DEVICE_ID);
            intNode = node.updateIntgBridge(DeviceId.deviceId(deviceIdStr));
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        } else {
            intNode = node;
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        }

        // if (intNode.extBridge() == null) {
        //     String deviceIdStr = genDpid(deviceIdCounter.incrementAndGet());
        //     checkNotNull(deviceIdStr, ERR_NULL_DEVICE_ID);
        //     extNode = intNode.updateExtBridge(DeviceId.deviceId(deviceIdStr));
        //     checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
        //             NOT_DUPLICATED_MSG, extNode.extBridge());
        // } else {
        //     extNode = intNode;
        //     checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
        //             NOT_DUPLICATED_MSG, extNode.extBridge());
        // }

        // TODO: Remove useless local bridge in the future
        if (node.localBridge() == null) {
            String deviceIdStr = genDpid(deviceIdCounter.incrementAndGet());
            checkNotNull(deviceIdStr, ERR_NULL_DEVICE_ID);
            localNode = intNode.updateLocalBridge(DeviceId.deviceId(deviceIdStr));
            checkArgument(!hasLocalBridge(localNode.localBridge(), localNode.hostname()),
                    NOT_DUPLICATED_MSG, localNode.localBridge());
        } else {
            localNode = intNode;
            checkArgument(!hasLocalBridge(localNode.localBridge(), localNode.hostname()),
                    NOT_DUPLICATED_MSG, localNode.localBridge());
        }


        nodeStore.createNode(localNode);
        log.info(String.format(MSG_NODE, localNode.hostname(), MSG_CREATED));
    }

    // Updates of k8s node
    @Override
    public void updateNode(K8sNode node) {
        checkNotNull(node, ERR_NULL_NODE);

        K8sNode intNode;
        K8sNode extNode;
        K8sNode localNode;

        K8sNode existingNode = nodeStore.node(node.hostname());
        checkNotNull(existingNode, ERR_NULL_NODE);

        DeviceId existIntgBridge = nodeStore.node(node.hostname()).intgBridge();

        if (node.intgBridge() == null) {
            intNode = node.updateIntgBridge(existIntgBridge);
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        } else {
            intNode = node;
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        }

        DeviceId existExtBridge = nodeStore.node(node.hostname()).extBridge();

        if(intNode.type().equals(K8sNode.Type.EXTOVS)){
            if (intNode.extBridge() == null) {
                extNode = intNode.updateExtBridge(existExtBridge);
                checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
                        NOT_DUPLICATED_MSG, extNode.extBridge());
            } else {
                extNode = intNode;
                checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
                        NOT_DUPLICATED_MSG, extNode.extBridge());
            }
            nodeStore.updateNode(extNode);
            log.info(String.format(MSG_NODE, extNode.hostname(), MSG_UPDATED));
        } else {
            // TODO: Remove useless local bridge in the future
            DeviceId existLocalBridge = nodeStore.node(node.hostname()).localBridge();
            if (intNode.localBridge() == null) {
                localNode = intNode.updateLocalBridge(existLocalBridge);
                checkArgument(!hasLocalBridge(localNode.localBridge(), localNode.hostname()),
                        NOT_DUPLICATED_MSG, localNode.localBridge());
            } else {
                localNode = intNode;
                checkArgument(!hasLocalBridge(localNode.localBridge(), localNode.hostname()),
                        NOT_DUPLICATED_MSG, localNode.localBridge());
            }
            nodeStore.updateNode(localNode);
            log.info(String.format(MSG_NODE, localNode.hostname(), MSG_UPDATED));
        }
    }

    // removement of k8s node
    @Override
    public K8sNode removeNode(String hostname) {
        checkArgument(!Strings.isNullOrEmpty(hostname), ERR_NULL_HOSTNAME);
        K8sNode node = nodeStore.removeNode(hostname);
        log.info(String.format(MSG_NODE, hostname, MSG_REMOVED));
        return node;
    }

    @Override
    public void createExtOvsNode(K8sNode node) {
        checkNotNull(node, ERR_NULL_NODE);

        K8sNode intNode;
        K8sNode extNode;

        if (node.intgBridge() == null) {
            String deviceIdStr = genDpid(deviceIdCounter.incrementAndGet());
            checkNotNull(deviceIdStr, ERR_NULL_DEVICE_ID);
            intNode = node.updateIntgBridge(DeviceId.deviceId(deviceIdStr));
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        } else {
            intNode = node;
            checkArgument(!hasIntgBridge(intNode.intgBridge(), intNode.hostname()),
                    NOT_DUPLICATED_MSG, intNode.intgBridge());
        }

        if (intNode.extBridge() == null) {
            String deviceIdStr = genDpid(deviceIdCounter.incrementAndGet());
            checkNotNull(deviceIdStr, ERR_NULL_DEVICE_ID);
            extNode = intNode.updateExtBridge(DeviceId.deviceId(deviceIdStr));
            checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
                    NOT_DUPLICATED_MSG, extNode.extBridge());
        } else {
            extNode = intNode;
            checkArgument(!hasExtBridge(extNode.extBridge(), extNode.hostname()),
                    NOT_DUPLICATED_MSG, extNode.extBridge());
        }

        nodeStore.createNode(extNode);
        log.info(String.format(MSG_NODE, extNode.hostname(), MSG_CREATED));
    }

    @Override
    public Set<K8sNode> nodes() {
        Predicate<K8sNode> isNotExtovsType = node -> node.type() != EXTOVS;
        Set<K8sNode> nodes = nodeStore.nodes().stream()
            .filter(isNotExtovsType)
            .collect(Collectors.toSet());
        return ImmutableSet.copyOf(nodes);
    }

    @Override
    public Set<K8sNode> nodes(Type type) {
        Set<K8sNode> nodes = nodeStore.nodes().stream()
                .filter(node -> Objects.equals(node.type(), type))
                .collect(Collectors.toSet());
        return ImmutableSet.copyOf(nodes);
    }

    @Override
    public Set<K8sNode> completeNodes() {
        Predicate<K8sNode> isNodeComplete = node -> node.state() == COMPLETE;
        Predicate<K8sNode> isNotExtovsType = node -> node.type() != EXTOVS;
        Set<K8sNode> nodes = nodeStore.nodes().stream()
                .filter(isNodeComplete.and(isNotExtovsType))
                .collect(Collectors.toSet());
        return ImmutableSet.copyOf(nodes);
    }

    @Override
    public Set<K8sNode> completeNodes(Type type) {
        Set<K8sNode> nodes = nodeStore.nodes().stream()
                .filter(node -> node.type() == type &&
                        node.state() == COMPLETE)
                .collect(Collectors.toSet());
        return ImmutableSet.copyOf(nodes);
    }

    @Override
    public K8sNode node(String hostname) {
        return nodeStore.node(hostname);
    }

    // TODO: need to differentiate integration bridge and external bridge
    @Override
    public K8sNode node(DeviceId deviceId) {
        return nodeStore.nodes().stream()
                .filter(node -> Objects.equals(node.intgBridge(), deviceId) ||
                        Objects.equals(node.ovsdb(), deviceId))
                .findFirst().orElse(null);
    }

    private boolean hasIntgBridge(DeviceId deviceId, String hostname) {
        Optional<K8sNode> existNode = nodeStore.nodes().stream()
                .filter(n -> !n.hostname().equals(hostname))
                .filter(n -> deviceId.equals(n.intgBridge()))
                .findFirst();

        return existNode.isPresent();
    }

    private boolean hasExtBridge(DeviceId deviceId, String hostname) {
        Optional<K8sNode> existNode = nodeStore.nodes().stream()
                .filter(n -> !n.hostname().equals(hostname))
                .filter(n -> deviceId.equals(n.extBridge()))
                .findFirst();

        return existNode.isPresent();
    }

    private boolean hasLocalBridge(DeviceId deviceId, String hostname) {
        Optional<K8sNode> existNode = nodeStore.nodes().stream()
                .filter(n -> !n.hostname().equals(hostname))
                .filter(n -> deviceId.equals(n.localBridge()))
                .findFirst();

        return existNode.isPresent();
    }

    private class InternalNodeStoreDelegate implements K8sNodeStoreDelegate {

        @Override
        public void notify(K8sNodeEvent event) {
            if (event != null) {
                log.trace("send kubernetes node event {}", event);
                process(event);
            }
        }
    }
}
