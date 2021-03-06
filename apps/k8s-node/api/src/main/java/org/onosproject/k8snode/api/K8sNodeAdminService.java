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
package org.onosproject.k8snode.api;

/**
 * Service for administering inventory of Kubernetes nodes.
 */
public interface K8sNodeAdminService extends K8sNodeService {

    /**
     * Creates a new node.
     *
     * @param node kubernetes node
     */
    void createNode(K8sNode node);

    /**
     * Updates the node.
     *
     * @param node kubernetes node
     */
    void updateNode(K8sNode node);

    /**
     * Removes the node.
     *
     * @param hostname kubernetes node hostname
     * @return removed node; null if the node does not exist
     */
    K8sNode removeNode(String hostname);

    /**
     * Creates a external OvS node
     * 
     * @param node External OvS node
     */
    void createExtOvsNode(K8sNode node);
}
