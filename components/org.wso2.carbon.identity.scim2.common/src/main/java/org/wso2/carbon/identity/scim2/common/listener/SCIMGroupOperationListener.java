/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.com).
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.scim2.common.listener;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.AbstractIdentityGroupOperationEventListener;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.scim2.common.DAO.GroupDAO;
import org.wso2.carbon.identity.scim2.common.exceptions.IdentitySCIMException;
import org.wso2.carbon.identity.scim2.common.utils.SCIMCommonConstants;
import org.wso2.carbon.identity.scim2.common.utils.SCIMCommonUtils;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.common.Group;
import org.wso2.carbon.user.core.model.Condition;
import org.wso2.carbon.user.core.model.ExpressionCondition;
import org.wso2.carbon.user.core.model.OperationalCondition;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.charon3.core.schema.SCIMConstants;

import java.util.List;
import java.util.Map;

import static org.wso2.carbon.identity.scim2.common.utils.SCIMCommonUtils.buildSearchAttributeValue;
import static org.wso2.carbon.user.core.UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME;

/**
 * Scim group operation handler implementation.
 */
public class SCIMGroupOperationListener extends AbstractIdentityGroupOperationEventListener {

    private static final Log log = LogFactory.getLog(SCIMGroupOperationListener.class);
    private static final String SQL_FILTERING_DELIMITER = "%";

    @Override
    public int getExecutionOrderId() {

        int orderId = getOrderId();
        if (orderId != IdentityCoreConstants.EVENT_LISTENER_ORDER_ID) {
            return orderId;
        }
        return 1;
    }

    @Override
    public boolean postGetGroupsListOfUserByUserId(String userId, List<Group> groupList,
                                                   UserStoreManager userStoreManager) throws UserStoreException {

        if (CollectionUtils.isEmpty(groupList)) {
            // To do filtering in IDN_SCIM_GROUP, we need group names. If the list is empty, we cannot do that.
            return true;
        }

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        GroupDAO groupDAO = new GroupDAO();
        for (Group group : groupList) {
            // We need to only provide the group name and group id.
            try {
                group.setGroupID(groupDAO.getGroupIdByName(tenantId, group.getGroupName()));
            } catch (IdentitySCIMException e) {
                throw new UserStoreException(String.format("Error occurred while getting the group id of " +
                        "group: %s in tenant: %s", group.getGroupName(), tenantId), e);
            }
        }
        return true;
    }

    @Override
    public boolean postGetGroupIdByName(String groupName, Group group, UserStoreManager userStoreManager)
            throws UserStoreException {

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Retrieving group with name: %s from tenant: %s", groupName, tenantId));
        }
        String groupId;
        GroupDAO groupDAO = new GroupDAO();
        try {
            groupId = groupDAO.getGroupIdByName(tenantId, groupName);
        } catch (IdentitySCIMException e) {
            throw new UserStoreException(String.format("Error occurred while getting the group id of " +
                    "group: %s in tenant: %s", groupName, tenantId), e);
        }
        if (StringUtils.isBlank(groupId)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No group found with the group name: %s in tenant: %s", groupName, tenantId));
            }
            return true;
        }
        String domainName = UserCoreUtil.extractDomainFromName(groupName);
        if (group == null) {
            group = new Group(groupId);
            group.setGroupName(resolveGroupName(groupName, domainName));
            group.setUserStoreDomain(domainName);
            group.setDisplayName(UserCoreUtil.removeDomainFromName(groupName));
        } else {
            group.setGroupID(groupId);
        }
        return true;
    }

    @Override
    public boolean postGetGroupNameById(String groupID, Group group, UserStoreManager userStoreManager)
            throws UserStoreException {

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        String groupName;
        GroupDAO groupDAO = new GroupDAO();
        try {
            groupName = groupDAO.getGroupNameById(tenantId, groupID);
            if (StringUtils.isBlank(groupName)) {
                log.error(String.format("No group found with id: %s in tenant: %s", groupID, tenantId));
                return true;
            }
        } catch (IdentitySCIMException e) {
            throw new UserStoreException(String.format("Error occurred while getting the group name of " +
                    "group: %s in tenant: %s", groupID, tenantId), e);
        }
        if (group == null) {
            group = new Group(groupID);
        }
        String domainName = UserCoreUtil.extractDomainFromName(groupName);
        group.setGroupName(resolveGroupName(groupName, domainName));
        group.setUserStoreDomain(UserCoreUtil.extractDomainFromName(groupName));
        group.setDisplayName(UserCoreUtil.removeDomainFromName(groupName));
        return true;
    }

    @Override
    public boolean postGetGroupById(String groupID, List<String> requestedClaims, Group group,
                                    UserStoreManager userStoreManager) throws UserStoreException {

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Retrieving group with id: %s from tenant: %s", groupID, tenantId));
        }
        String groupName;
        Map<String, String> attributes;
        GroupDAO groupDAO = new GroupDAO();
        try {
            groupName = groupDAO.getGroupNameById(tenantId, groupID);
            if (StringUtils.isBlank(groupName)) {
                log.error(String.format("No group found with id: %s in tenant: %s", groupID, tenantId));
                return true;
            }
            attributes = groupDAO.getSCIMGroupAttributes(tenantId, groupName);
        } catch (IdentitySCIMException e) {
            throw new UserStoreException(String.format("Error occurred while getting the group attributes of " +
                    "group: %s in tenant: %s", groupID, tenantId), e);
        }
        // At this point there is definitely a matching group for the given id.
        String domainName = UserCoreUtil.extractDomainFromName(groupName);
        if (group == null) {
            group = new Group(groupID, resolveGroupName(groupName, domainName));
        } else {
            group.setGroupID(groupID);
            group.setGroupName(resolveGroupName(groupName, domainName));
        }
        // Removing the userstore domain name from the display name and setting it as the userstore domain of the group.
        group.setDisplayName(UserCoreUtil.removeDomainFromName(groupName));
        group.setUserStoreDomain(domainName);

        // Set mandatory attributes.
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (SCIMConstants.CommonSchemaConstants.ID_URI.equals(entry.getKey())) {
                group.setGroupID(entry.getValue());
            } else if (SCIMConstants.CommonSchemaConstants.CREATED_URI.equals(entry.getKey())) {
                group.setCreatedDate(entry.getValue());
            } else if (SCIMConstants.CommonSchemaConstants.LAST_MODIFIED_URI.equals(entry.getKey())) {
                group.setLastModifiedDate(entry.getValue());
            } else if (SCIMConstants.CommonSchemaConstants.LOCATION_URI.equals(entry.getKey())) {
                group.setLocation(SCIMCommonUtils.getSCIMGroupURL(groupID));
            }
        }
        return true;
    }

    @Override
    public boolean postGetGroupByName(String groupName, List<String> requestedClaims, Group group,
                                      UserStoreManager userStoreManager) throws UserStoreException {

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Retrieving group with name: %s from tenant: %s", groupName, tenantId));
        }
        Map<String, String> attributes;
        GroupDAO groupDAO = new GroupDAO();
        try {
            attributes = groupDAO.getSCIMGroupAttributes(tenantId, groupName);
        } catch (IdentitySCIMException e) {
            throw new UserStoreException(String.format("Error occurred while getting the group attributes of " +
                    "group: %s in tenant: %s", groupName, tenantId), e);
        }
        if (MapUtils.isEmpty(attributes)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No group found with name: %s in tenant: %s", groupName, tenantId));
            }
            return true;
        }
        String groupId = attributes.get(SCIMConstants.CommonSchemaConstants.ID_URI);
        String domainName = UserCoreUtil.extractDomainFromName(groupName);
        if (group == null) {
            group = new Group(groupId, resolveGroupName(groupName, domainName));
        }
        // Set mandatory attributes.
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (SCIMConstants.CommonSchemaConstants.ID_URI.equals(entry.getKey())) {
                group.setGroupID(groupId);
            } else if (SCIMConstants.CommonSchemaConstants.CREATED_URI.equals(entry.getKey())) {
                group.setCreatedDate(entry.getValue());
            } else if (SCIMConstants.CommonSchemaConstants.LAST_MODIFIED_URI.equals(entry.getKey())) {
                group.setLastModifiedDate(entry.getValue());
            } else if (SCIMConstants.CommonSchemaConstants.LOCATION_URI.equals(entry.getKey())) {
                group.setLocation(SCIMCommonUtils.getSCIMGroupURL(groupId));
            }
        }
        group.setDisplayName(UserCoreUtil.removeDomainFromName(groupName));
        group.setUserStoreDomain(domainName);
        return true;
    }

    @Override
    public boolean postListGroups(Condition condition, int limit, int offset, String domain, String sortBy,
                                  String sortOrder, List<Group> groupsList, UserStoreManager userStoreManager)
            throws UserStoreException {

        int tenantId = userStoreManager.getTenantId();
        AbstractUserStoreManager abstractUserStoreManager = ((AbstractUserStoreManager) userStoreManager);
        boolean isGroupIdEnabled = abstractUserStoreManager.isUniqueGroupIdEnabled();
        /*
         * isGroupIdEnabled equal to false indicates that the given userstore only support the legacy behaviour. In
         * that case we need to support getting group details from IDN_SCIM_GROUP table.
         */
        if (isGroupIdEnabled) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("SCIMGroupOperationListener will not be executed for userstore: %s in " +
                                "tenant %s since group id support is available in the userstore manager",
                        abstractUserStoreManager.getRealmConfiguration().getRealmProperty(PROPERTY_DOMAIN_NAME),
                        tenantId));
            }
            return true;
        }
        /*
         * Following fill be executed for backward compatible userstores. Those userstores did not have multi
         * attribute filtering. Therefore, we do not need to provide support for that.
         */
        if (condition instanceof OperationalCondition) {
            throw new UserStoreException("OperationalCondition filtering is not supported by userstore: " +
                    userStoreManager.getClass());
        }
        ExpressionCondition expressionCondition = (ExpressionCondition) condition;
        String attributeName = resolveGroupAttributeWithSCIMSchema(expressionCondition.getAttributeName(), tenantId);
        String attributeValue = buildSearchAttributeValue(attributeName, expressionCondition.getOperation(),
                expressionCondition.getAttributeValue(), SQL_FILTERING_DELIMITER);
        GroupDAO groupDAO = new GroupDAO();
        try {
            String[] groupNames = groupDAO.getGroupNameList(attributeName, attributeValue, tenantId, domain);
            if (ArrayUtils.isEmpty(groupNames)) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("No groups found for the filter in userstore: %s in tenant: %s",
                            domain, tenantId));
                }
                return true;
            }
            // Get details of the groups.
            for (String groupName : groupNames) {
                Map<String, String> attributes = groupDAO.getSCIMGroupAttributes(tenantId, groupName);
                String groupId = attributes.get(SCIMConstants.CommonSchemaConstants.ID_URI);
                String domainName = UserCoreUtil.extractDomainFromName(groupName);
                Group group = new Group(groupId, resolveGroupName(groupName, domainName));
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    if (SCIMConstants.CommonSchemaConstants.CREATED_URI.equals(entry.getKey())) {
                        group.setCreatedDate(entry.getValue());
                    } else if (SCIMConstants.CommonSchemaConstants.LAST_MODIFIED_URI.equals(entry.getKey())) {
                        group.setLastModifiedDate(entry.getValue());
                    } else if (SCIMConstants.CommonSchemaConstants.LOCATION_URI.equals(entry.getKey())) {
                        group.setLocation(SCIMCommonUtils.getSCIMGroupURL(groupId));
                    }
                }
                group.setDisplayName(UserCoreUtil.removeDomainFromName(groupName));
                group.setUserStoreDomain(domainName);
                groupsList.add(group);
            }
        } catch (IdentitySCIMException e) {
            throw new UserStoreException(String.format("Error occurred while getting the group list in userstore: %s " +
                    "in tenant: %s", domain, tenantId), e);
        }
        return true;
    }

    /**
     * Get the corresponding schema that is associated with the group attribute.
     *
     * @param attributeName Attribute name.
     * @param tenantId      Tenant Id.
     * @return Corresponding scim schema.
     * @throws UserStoreException If the attributeName is empty or there is no matching schema for the given
     *                            attribute attributeName.
     */
    private String resolveGroupAttributeWithSCIMSchema(String attributeName, int tenantId) throws UserStoreException {

        if (StringUtils.isBlank(attributeName)) {
            throw new UserStoreException("Group attribute cannot be empty");
        }
        String schema = null;
        Map<String, String> groupAttributeSchemaMap = SCIMCommonConstants.getGroupAttributeSchemaMap();
        for (String key : groupAttributeSchemaMap.keySet()) {
            if (attributeName.equalsIgnoreCase(groupAttributeSchemaMap.get(key))) {
                schema = key;
            }
        }
        if (schema == null) {
            throw new UserStoreException(String.format("No scim schema to attribute mapping for " +
                    "attribute:%s for tenant: %s", attributeName, tenantId));
        }
        return schema;
    }

    /**
     * Resolve whether the domain name should be prepended to the group name.
     *
     * @param groupName           Group name.
     * @param userstoreDomainName Userstore domain name.
     * @return Resolved gr
     */
    private String resolveGroupName(String groupName, String userstoreDomainName) {

        // Do not add PRIMARY to the the groups in the primary userstore.
        if (UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME.equalsIgnoreCase(userstoreDomainName)) {
            return UserCoreUtil.removeDomainFromName(groupName);
        }
        return UserCoreUtil.addDomainToName(groupName, userstoreDomainName);
    }
}
