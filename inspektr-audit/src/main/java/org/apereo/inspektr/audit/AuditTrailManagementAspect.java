/**
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apereo.inspektr.audit;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.apereo.inspektr.audit.annotation.Audit;
import org.apereo.inspektr.audit.annotation.Audits;
import org.apereo.inspektr.audit.spi.AuditActionResolver;
import org.apereo.inspektr.audit.spi.AuditResourceResolver;
import org.apereo.inspektr.common.spi.PrincipalResolver;
import org.apereo.inspektr.common.spi.ClientInfoResolver;
import org.apereo.inspektr.common.spi.DefaultClientInfoResolver;
import org.apereo.inspektr.common.web.ClientInfo;
import org.apereo.inspektr.audit.spi.support.BooleanAuditActionResolver;
import org.apereo.inspektr.audit.spi.support.DefaultAuditActionResolver;
import org.apereo.inspektr.audit.spi.support.ObjectCreationAuditActionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A POJO style aspect modularizing management of an audit trail data concern.
 *
 * @author Dmitriy Kopylenko
 * @author Scott Battaglia
 * @version $Revision$ $Date$
 * @since 1.0
 */
@Aspect
public class AuditTrailManagementAspect {

    private static final Logger LOG = LoggerFactory.getLogger(AuditTrailManagementAspect.class);
    
    private final PrincipalResolver auditPrincipalResolver;

    private final Map<String, AuditActionResolver> auditActionResolvers;

    private final Map<String, AuditResourceResolver> auditResourceResolvers;

    private final List<AuditTrailManager> auditTrailManagers;

    private final String applicationCode;

    private ClientInfoResolver clientInfoResolver = new DefaultClientInfoResolver();

    private boolean failOnAuditFailures = true;
    
    /**
     * Constructs an AuditTrailManagementAspect with the following parameters.  Also, registers some default AuditActionResolvers including the
     * {@link DefaultAuditActionResolver}, the {@link BooleanAuditActionResolver} and the {@link ObjectCreationAuditActionResolver}.
     *
     * @param applicationCode            the overall code that identifies this application.
     * @param auditablePrincipalResolver the resolver which will locate principals.
     * @param auditTrailManagers         the list of managers to write the audit trail out to.
     * @param auditActionResolverMap     the map of resolvers by name provided in the annotation on the method.
     * @param auditResourceResolverMap   the map of resolvers by the name provided in the annotation on the method.
     */
    public AuditTrailManagementAspect(final String applicationCode, final PrincipalResolver auditablePrincipalResolver, final List<AuditTrailManager> auditTrailManagers, final Map<String, AuditActionResolver> auditActionResolverMap, final Map<String, AuditResourceResolver> auditResourceResolverMap) {
        this.auditPrincipalResolver = auditablePrincipalResolver;
        this.auditTrailManagers = auditTrailManagers;
        this.applicationCode = applicationCode;
        this.auditActionResolvers = auditActionResolverMap;
        this.auditResourceResolvers = auditResourceResolverMap;

    }
    
    @Around(value = "@annotation(audits)", argNames = "audits")
    public Object handleAuditTrail(final ProceedingJoinPoint joinPoint, final Audits audits) throws Throwable {
        Object retVal = null;
        String currentPrincipal = null;
        final String[] actions = new String[audits.value().length];
        final String[][] auditableResources = new String[audits.value().length][];
        try {
            retVal = joinPoint.proceed();
            currentPrincipal = this.auditPrincipalResolver.resolveFrom(joinPoint, retVal);

            if (currentPrincipal != null) {
                for (int i = 0; i < audits.value().length; i++) {
                    final AuditActionResolver auditActionResolver = this.auditActionResolvers.get(audits.value()[i].actionResolverName());
                    final AuditResourceResolver auditResourceResolver = this.auditResourceResolvers.get(audits.value()[i].resourceResolverName());
                    auditableResources[i] = auditResourceResolver.resolveFrom(joinPoint, retVal);
                    actions[i] = auditActionResolver.resolveFrom(joinPoint, retVal, audits.value()[i]);
                }
            }
            return retVal;
        } catch (final Exception e) {
            currentPrincipal = this.auditPrincipalResolver.resolveFrom(joinPoint, e);

            if (currentPrincipal != null) {
                for (int i = 0; i < audits.value().length; i++) {
                    auditableResources[i] = this.auditResourceResolvers.get(audits.value()[i].resourceResolverName()).resolveFrom(joinPoint, e);
                    actions[i] = auditActionResolvers.get(audits.value()[i].actionResolverName()).resolveFrom(joinPoint, e, audits.value()[i]);
                }
            }
            throw e;
        } finally {
            for (int i = 0; i < audits.value().length; i++) {
                executeAuditCode(currentPrincipal, auditableResources[i], joinPoint, retVal, actions[i], audits.value()[i]);
            }
        }
    }

    @Around(value = "@annotation(audit)", argNames = "audit")
    public Object handleAuditTrail(final ProceedingJoinPoint joinPoint, final Audit audit) throws Throwable {
        final AuditActionResolver auditActionResolver = this.auditActionResolvers.get(audit.actionResolverName());
        final AuditResourceResolver auditResourceResolver = this.auditResourceResolvers.get(audit.resourceResolverName());

        String currentPrincipal = null;
        String[] auditResource = new String[]{null};
        String action = null;
        Object retVal = null;
        try {
            retVal = joinPoint.proceed();

            currentPrincipal = this.auditPrincipalResolver.resolveFrom(joinPoint, retVal);
            auditResource = auditResourceResolver.resolveFrom(joinPoint, retVal);
            action = auditActionResolver.resolveFrom(joinPoint, retVal, audit);

            return retVal;
        } catch (final Exception e) {
            currentPrincipal = this.auditPrincipalResolver.resolveFrom(joinPoint, e);
            auditResource = auditResourceResolver.resolveFrom(joinPoint, e);
            action = auditActionResolver.resolveFrom(joinPoint, e, audit);
            throw e;
        } finally {
            executeAuditCode(currentPrincipal, auditResource, joinPoint, retVal, action, audit);
        }
    }

    private void executeAuditCode(final String currentPrincipal, final String[] auditableResources, final ProceedingJoinPoint joinPoint, final Object retVal, final String action, final Audit audit) {
        final String applicationCode = (audit.applicationCode() != null && audit.applicationCode().length() > 0) ? audit.applicationCode() : this.applicationCode;
        final ClientInfo clientInfo = this.clientInfoResolver.resolveFrom(joinPoint, retVal);
        final Date actionDate = new Date();
        final AuditPointRuntimeInfo runtimeInfo = new AspectJAuditPointRuntimeInfo(joinPoint);
        for (final String auditableResource : auditableResources) {
            final AuditActionContext auditContext =
                    new AuditActionContext(currentPrincipal, auditableResource, action, applicationCode,
                            actionDate, clientInfo.getClientIpAddress(), clientInfo.getServerIpAddress(), runtimeInfo);
           
            try {
                for (final AuditTrailManager manager : auditTrailManagers) {
                    manager.record(auditContext);
                }
            } catch (final Throwable e) {
                if (this.failOnAuditFailures) {
                    throw e;
                }
                LOG.error("Failed to record audit context for " 
                        + auditContext.getActionPerformed()
                        + " and principal " + auditContext.getPrincipal(), e);
            }
        }
    }

    public void setFailOnAuditFailures(final boolean failOnAuditFailures) {
        this.failOnAuditFailures = failOnAuditFailures;
    }

    public void setClientInfoResolver(final ClientInfoResolver factory) {
        this.clientInfoResolver = factory;
    }
}
