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
package org.onosproject.k8snode.web;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.codec.CodecService;
import org.onosproject.k8snode.api.K8sApiConfig;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.codec.K8sApiConfigCodec;
import org.onosproject.k8snode.codec.K8sNodeCodec;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Implementation of the JSON codec brokering service for K8sNode.
 */
@Component(immediate = true)
public class K8sNodeCodecRegister {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CodecService codecService;

    @Activate
    protected void activate() {

        codecService.registerCodec(K8sNode.class, new K8sNodeCodec());
        codecService.registerCodec(K8sApiConfig.class, new K8sApiConfigCodec());

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {

        codecService.unregisterCodec(K8sNode.class);
        codecService.unregisterCodec(K8sApiConfig.class);

        log.info("Stopped");
    }
}
