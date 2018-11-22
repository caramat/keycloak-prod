/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.federation.ldap;

import java.util.List;
import java.util.Set;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.ModelException;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.storage.ldap.LDAPConfig;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.LDAPUtils;
import org.keycloak.storage.ldap.idm.model.LDAPDn;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.mappers.membership.LDAPGroupMapperMode;
import org.keycloak.storage.ldap.mappers.membership.MembershipType;
import org.keycloak.storage.ldap.mappers.membership.group.GroupLDAPStorageMapper;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.storage.ldap.mappers.membership.group.GroupMapperConfig;
import org.keycloak.testsuite.runonserver.RunOnServerDeployment;
import org.keycloak.testsuite.util.LDAPRule;
import org.keycloak.testsuite.util.LDAPTestUtils;

import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.AUTH_SERVER_CURRENT;
import static org.keycloak.testsuite.util.LDAPTestUtils.getGroupDescriptionLDAPAttrName;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LDAPGroupMapperTest extends AbstractLDAPTest {

    @ClassRule
    public static LDAPRule ldapRule = new LDAPRule();

    @Deployment
    @TargetsContainer(AUTH_SERVER_CURRENT)
    public static WebArchive deploy() {
        return RunOnServerDeployment.create(UserResource.class, AbstractLDAPTest.class)
                .addPackages(true,
                        "org.keycloak.testsuite",
                        "org.keycloak.testsuite.federation.ldap");
    }


    @Override
    protected LDAPRule getLDAPRule() {
        return ldapRule;
    }

    @Override
    protected void afterImportTestRealm() {
        testingClient.testing().ldap(TEST_REALM_NAME).prepareGroupsLDAPTest();
    }



    @Test
    public void test01_ldapOnlyGroupMappings() {
        test01_ldapOnlyGroupMappings(true);
    }


    protected void test01_ldapOnlyGroupMappings(boolean importEnabled) {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            LDAPTestUtils.updateGroupMapperConfigOptions(mapperModel, GroupMapperConfig.MODE, LDAPGroupMapperMode.LDAP_ONLY.toString());
            appRealm.updateComponent(mapperModel);

            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            // 1 - Grant some groups in LDAP

            // This group should already exists as it was imported from LDAP
            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            john.joinGroup(group1);

            // This group should already exists as it was imported from LDAP
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            mary.joinGroup(group11);

            // This group should already exists as it was imported from LDAP
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");
            john.joinGroup(group12);
            mary.joinGroup(group12);

            // This group should already exists as it was imported from LDAP
            GroupModel groupWithSlashesInName = KeycloakModelUtils.findGroupByPath(appRealm, "Team 2016/2017");
            john.joinGroup(groupWithSlashesInName);
            mary.joinGroup(groupWithSlashesInName);

            // This group should already exists as it was imported from LDAP
            GroupModel groupChildWithSlashesInName = KeycloakModelUtils.findGroupByPath(appRealm, "defaultGroup1/Team Child 2018/2019");
            john.joinGroup(groupChildWithSlashesInName);
            mary.joinGroup(groupChildWithSlashesInName);

            Assert.assertEquals("Team SubChild 2020/2021", KeycloakModelUtils.findGroupByPath(appRealm, "defaultGroup1/Team Child 2018/2019/Team SubChild 2020/2021").getName());
            Assert.assertEquals("defaultGroup14", KeycloakModelUtils.findGroupByPath(appRealm, "defaultGroup13/Team SubChild 2022/2023/A/B/C/D/E/defaultGroup14").getName());
            Assert.assertEquals("Team SubChild 2026/2027", KeycloakModelUtils.findGroupByPath(appRealm, "Team Root 2024/2025/A/B/C/D/defaultGroup15/Team SubChild 2026/2027").getName());
        });


        // 2 - Check that group mappings are not in local Keycloak DB (They are in LDAP).
        if (importEnabled) {
            testingClient.server().run(session -> {
                LDAPTestContext ctx = LDAPTestContext.init(session);
                RealmModel appRealm = ctx.getRealm();
                UserModel johnDb = session.userLocalStorage().getUserByUsername("johnkeycloak", appRealm);
                Set<GroupModel> johnDbGroups = johnDb.getGroups();
                Assert.assertEquals(2, johnDbGroups.size());
            });
        }


        // 3 - Check that group mappings are in LDAP and hence available through federation
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");
            GroupModel groupTeam20162017 = KeycloakModelUtils.findGroupByPath(appRealm, "Team 2016/2017");
            GroupModel groupTeamChild20182019 = KeycloakModelUtils.findGroupByPath(appRealm, "defaultGroup1/Team Child 2018/2019");
            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            Set<GroupModel> johnGroups = john.getGroups();
            Assert.assertEquals(4, johnGroups.size());
            Assert.assertTrue(johnGroups.contains(group1));
            Assert.assertFalse(johnGroups.contains(group11));
            Assert.assertTrue(johnGroups.contains(group12));
            Assert.assertTrue(johnGroups.contains(groupTeam20162017));
            Assert.assertTrue(johnGroups.contains(groupTeamChild20182019));

            // 4 - Check through userProvider
            List<UserModel> group1Members = session.users().getGroupMembers(appRealm, group1, 0, 10);
            List<UserModel> group11Members = session.users().getGroupMembers(appRealm, group11, 0, 10);
            List<UserModel> group12Members = session.users().getGroupMembers(appRealm, group12, 0, 10);
            List<UserModel> groupTeam20162017Members = session.users().getGroupMembers(appRealm, groupTeam20162017, 0, 10);
            List<UserModel> groupTeam20182019Members = session.users().getGroupMembers(appRealm, groupTeamChild20182019, 0, 10);

            Assert.assertEquals(1, group1Members.size());
            Assert.assertEquals("johnkeycloak", group1Members.get(0).getUsername());
            Assert.assertEquals(1, group11Members.size());
            Assert.assertEquals("marykeycloak", group11Members.get(0).getUsername());
            Assert.assertEquals(2, group12Members.size());
            Assert.assertEquals(2, groupTeam20162017Members.size());
            Assert.assertEquals(2, groupTeam20182019Members.size());

            // 4 - Delete some group mappings and check they are deleted

            john.leaveGroup(group1);
            john.leaveGroup(group12);
            john.leaveGroup(groupTeam20162017);
            john.leaveGroup(groupTeamChild20182019);

            mary.leaveGroup(group1);
            mary.leaveGroup(group12);
            mary.leaveGroup(groupTeam20162017);
            mary.leaveGroup(groupTeamChild20182019);

            johnGroups = john.getGroups();
            Assert.assertEquals(0, johnGroups.size());
        });
    }

    @Test
    public void test02_readOnlyGroupMappings() {
        test02_readOnlyGroupMappings(true);
    }



    protected void test02_readOnlyGroupMappings(boolean importEnabled) {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            LDAPTestUtils.updateGroupMapperConfigOptions(mapperModel, GroupMapperConfig.MODE, LDAPGroupMapperMode.READ_ONLY.toString());
            appRealm.updateComponent(mapperModel);

            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");

            // Add some group mappings directly into LDAP
            GroupLDAPStorageMapper groupMapper = LDAPTestUtils.getGroupMapper(mapperModel, ctx.getLdapProvider(), appRealm);

            LDAPObject maryLdap = ctx.getLdapProvider().loadLDAPUserByUsername(appRealm, "marykeycloak");
            groupMapper.addGroupMappingInLDAP(appRealm, group1, maryLdap);
            groupMapper.addGroupMappingInLDAP(appRealm, group11, maryLdap);

            // Add some group mapping to model
            mary.joinGroup(group12);

            // Assert that mary has both LDAP and DB mapped groups
            Set<GroupModel> maryGroups = mary.getGroups();
            Assert.assertEquals(5, maryGroups.size());
            Assert.assertTrue(maryGroups.contains(group1));
            Assert.assertTrue(maryGroups.contains(group11));
            Assert.assertTrue(maryGroups.contains(group12));
        });

        // Assert that access through DB will have just DB mapped groups
        if (importEnabled) {
            testingClient.server().run(session -> {
                LDAPTestContext ctx = LDAPTestContext.init(session);
                RealmModel appRealm = ctx.getRealm();

                GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
                GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
                GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");

                UserModel maryDB = session.userLocalStorage().getUserByUsername("marykeycloak", appRealm);

                Set<GroupModel> maryDBGroups = maryDB.getGroups();
                Assert.assertFalse(maryDBGroups.contains(group1));
                Assert.assertFalse(maryDBGroups.contains(group11));
                Assert.assertTrue(maryDBGroups.contains(group12));

                // Test the group mapping available for group12
                List<UserModel> group12Members = session.users().getGroupMembers(appRealm, group12, 0, 10);
                Assert.assertEquals(1, group12Members.size());
                Assert.assertEquals("marykeycloak", group12Members.get(0).getUsername());

                UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);
                mary.leaveGroup(group12);
            });
        } else {
            testingClient.server().run(session -> {
                LDAPTestContext ctx = LDAPTestContext.init(session);
                RealmModel appRealm = ctx.getRealm();

                GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");

                // Test the group mapping NOT available for group12
                List<UserModel> group12Members = session.users().getGroupMembers(appRealm, group12, 0, 10);
                Assert.assertEquals(0, group12Members.size());
            });
        }


        // Check through userProvider
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");
            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);
            UserModel mary = session.users().getUserByUsername("marykeycloak", appRealm);

            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            GroupLDAPStorageMapper groupMapper = LDAPTestUtils.getGroupMapper(mapperModel, ctx.getLdapProvider(), appRealm);
            LDAPObject maryLdap = ctx.getLdapProvider().loadLDAPUserByUsername(appRealm, "marykeycloak");

            List<UserModel> group1Members = session.users().getGroupMembers(appRealm, group1, 0, 10);
            List<UserModel> group11Members = session.users().getGroupMembers(appRealm, group11, 0, 10);
            List<UserModel> group12Members = session.users().getGroupMembers(appRealm, group12, 0, 10);
            Assert.assertEquals(1, group1Members.size());
            Assert.assertEquals("marykeycloak", group1Members.get(0).getUsername());
            Assert.assertEquals(1, group11Members.size());
            Assert.assertEquals("marykeycloak", group11Members.get(0).getUsername());

            try {
                mary.leaveGroup(group1);
                Assert.fail("It wasn't expected to successfully delete LDAP group mappings in READ_ONLY mode");
            } catch (ModelException expected) {
            }

            // Delete group mappings directly in LDAP
            LDAPObject ldapGroup = groupMapper.loadLDAPGroupByName("group1");
            groupMapper.deleteGroupMappingInLDAP(maryLdap, ldapGroup);

            ldapGroup = groupMapper.loadLDAPGroupByName("group11");
            groupMapper.deleteGroupMappingInLDAP(maryLdap, ldapGroup);
        });
    }


    @Test
    public void test03_importGroupMappings() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            LDAPTestUtils.updateGroupMapperConfigOptions(mapperModel, GroupMapperConfig.MODE, LDAPGroupMapperMode.IMPORT.toString());
            appRealm.updateComponent(mapperModel);

            // Add some group mappings directly in LDAP
            LDAPStorageProvider ldapProvider = LDAPTestUtils.getLdapProvider(session, ctx.getLdapModel());
            GroupLDAPStorageMapper groupMapper = LDAPTestUtils.getGroupMapper(mapperModel, ldapProvider, appRealm);

            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");

            LDAPObject robLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "robkeycloak");
            groupMapper.addGroupMappingInLDAP(appRealm, group11, robLdap);
            groupMapper.addGroupMappingInLDAP(appRealm, group12, robLdap);

            // Get user and check that he has requested groups from LDAP
            UserModel rob = session.users().getUserByUsername("robkeycloak", appRealm);
            Set<GroupModel> robGroups = rob.getGroups();

            Assert.assertFalse(robGroups.contains(group1));
            Assert.assertTrue(robGroups.contains(group11));
            Assert.assertTrue(robGroups.contains(group12));

            // Delete some group mappings in LDAP and check that it doesn't have any effect and user still has groups
            LDAPObject ldapGroup = groupMapper.loadLDAPGroupByName("group11");
            groupMapper.deleteGroupMappingInLDAP(robLdap, ldapGroup);

            ldapGroup = groupMapper.loadLDAPGroupByName("group12");
            groupMapper.deleteGroupMappingInLDAP(robLdap, ldapGroup);

            robGroups = rob.getGroups();
            Assert.assertTrue(robGroups.contains(group11));
            Assert.assertTrue(robGroups.contains(group12));

            // Delete group mappings through model and verifies that user doesn't have them anymore
            rob.leaveGroup(group11);
            rob.leaveGroup(group12);
            robGroups = rob.getGroups();
            Assert.assertEquals(2, robGroups.size());
        });
    }


    // KEYCLOAK-2682
    @Test
    public void test04_groupReferencingNonExistentMember() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            // Ignoring this test on ActiveDirectory as it's not allowed to have LDAP group referencing nonexistent member. KEYCLOAK-2682 was related to OpenLDAP TODO: Better solution than programmatic...
            LDAPConfig config = ctx.getLdapProvider().getLdapIdentityStore().getConfig();
            if (config.isActiveDirectory()) {
                return;
            }

            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            LDAPTestUtils.updateGroupMapperConfigOptions(mapperModel, GroupMapperConfig.MODE, LDAPGroupMapperMode.LDAP_ONLY.toString());
            appRealm.updateComponent(mapperModel);

            String descriptionAttrName = getGroupDescriptionLDAPAttrName(ctx.getLdapProvider());

            // 1 - Add some group to LDAP for testing
            LDAPStorageProvider ldapProvider = LDAPTestUtils.getLdapProvider(session, ctx.getLdapModel());
            GroupLDAPStorageMapper groupMapper = LDAPTestUtils.getGroupMapper(mapperModel, ldapProvider, appRealm);
            LDAPObject group2 = LDAPTestUtils.createLDAPGroup(session, appRealm, ctx.getLdapModel(), "group2", descriptionAttrName, "group2 - description");

            // 2 - Add one existing user rob to LDAP group
            LDAPObject jamesLdap = ldapProvider.loadLDAPUserByUsername(appRealm, "jameskeycloak");
            LDAPUtils.addMember(ldapProvider, MembershipType.DN, LDAPConstants.MEMBER, "not-used", group2, jamesLdap, false);

            // 3 - Add non-existing user to LDAP group
            LDAPDn nonExistentDn = LDAPDn.fromString(ldapProvider.getLdapIdentityStore().getConfig().getUsersDn());
            nonExistentDn.addFirst(jamesLdap.getRdnAttributeName(), "nonexistent");
            LDAPObject nonExistentLdapUser = new LDAPObject();
            nonExistentLdapUser.setDn(nonExistentDn);
            LDAPUtils.addMember(ldapProvider, MembershipType.DN, LDAPConstants.MEMBER, "not-used", group2, nonExistentLdapUser, true);

            // 4 - Check group members. Just existing user rob should be present
            groupMapper.syncDataFromFederationProviderToKeycloak(appRealm);
            GroupModel kcGroup2 = KeycloakModelUtils.findGroupByPath(appRealm, "/group2");
            List<UserModel> groupUsers = session.users().getGroupMembers(appRealm, kcGroup2, 0, 5);
            Assert.assertEquals(1, groupUsers.size());
            UserModel rob = groupUsers.get(0);
            Assert.assertEquals("jameskeycloak", rob.getUsername());

        });
    }


    // KEYCLOAK-5848
    // Test GET_GROUPS_FROM_USER_MEMBEROF_ATTRIBUTE with custom 'Member-Of LDAP Attribute'. As a workaround, we are testing this with custom attribute "street"
    // just because it's available on all the LDAP servers
    @Test
    public void test05_getGroupsFromUserMemberOfStrategyTest() throws Exception {
        ComponentRepresentation groupMapperRep = findMapperRepByName("groupsMapper");

        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            // Create street attribute mapper
            LDAPTestUtils.addUserAttributeMapper(appRealm, ctx.getLdapModel(), "streetMapper", "street", LDAPConstants.STREET);

            // Find DN of "group1"
            ComponentModel mapperModel = LDAPTestUtils.getSubcomponentByName(appRealm, ctx.getLdapModel(), "groupsMapper");
            GroupLDAPStorageMapper groupMapper = LDAPTestUtils.getGroupMapper(mapperModel, ctx.getLdapProvider(), appRealm);
            LDAPObject ldapGroup = groupMapper.loadLDAPGroupByName("group1");
            String ldapGroupDN = ldapGroup.getDn().toString();

            // Create new user in LDAP. Add him some "street" referencing existing LDAP Group
            LDAPObject carlos = LDAPTestUtils.addLDAPUser(ctx.getLdapProvider(), appRealm, "carloskeycloak", "Carlos", "Doel", "carlos.doel@email.org", ldapGroupDN, "1234");
            LDAPTestUtils.updateLDAPPassword(ctx.getLdapProvider(), carlos, "Password1");

            // Update group mapper
            LDAPTestUtils.updateGroupMapperConfigOptions(mapperModel,
                    GroupMapperConfig.USER_ROLES_RETRIEVE_STRATEGY, GroupMapperConfig.GET_GROUPS_FROM_USER_MEMBEROF_ATTRIBUTE,
                    GroupMapperConfig.MEMBEROF_LDAP_ATTRIBUTE, LDAPConstants.STREET);
            appRealm.updateComponent(mapperModel);
        });

        ComponentRepresentation streetMapperRep = findMapperRepByName("streetMapper");

        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            // Get user in Keycloak. Ensure that he is member of requested group
            UserModel carlos = session.users().getUserByUsername("carloskeycloak", appRealm);
            Set<GroupModel> carlosGroups = carlos.getGroups();

            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            GroupModel group11 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group11");
            GroupModel group12 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group12");

            Assert.assertTrue(carlosGroups.contains(group1));
            Assert.assertFalse(carlosGroups.contains(group11));
            Assert.assertFalse(carlosGroups.contains(group12));

            Assert.assertEquals(1, carlosGroups.size());
        });

        // Revert mappers
        testRealm().components().component(streetMapperRep.getId()).remove();
        groupMapperRep.getConfig().putSingle(GroupMapperConfig.USER_ROLES_RETRIEVE_STRATEGY, GroupMapperConfig.LOAD_GROUPS_BY_MEMBER_ATTRIBUTE);
        testRealm().components().component(groupMapperRep.getId()).update(groupMapperRep);
    }


    // KEYCLOAK-5017
    @Test
    public void test06_addingUserToNewKeycloakGroup() throws Exception {
        // Add some groups to Keycloak
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            GroupModel group3 = appRealm.createGroup("group3");
            session.realms().addTopLevelGroup(appRealm, group3);
            GroupModel group31 = appRealm.createGroup("group31");
            group3.addChild(group31);
            GroupModel group32 = appRealm.createGroup("group32");
            group3.addChild(group32);

            GroupModel group4 = appRealm.createGroup("group4");
            session.realms().addTopLevelGroup(appRealm, group4);

            GroupModel group14 = appRealm.createGroup("group14");
            GroupModel group1 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1");
            group1.addChild(group14);

        });

        // Add user to some newly created KC groups
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);

            GroupModel group4 =  KeycloakModelUtils.findGroupByPath(appRealm, "/group4");
            john.joinGroup(group4);

            GroupModel group31 = KeycloakModelUtils.findGroupByPath(appRealm, "/group3/group31");
            GroupModel group32 = KeycloakModelUtils.findGroupByPath(appRealm, "/group3/group32");

            john.joinGroup(group31);
            john.joinGroup(group32);

            GroupModel group14 = KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group14");
            john.joinGroup(group14);
        });

        // Check user group memberships
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            UserModel john = session.users().getUserByUsername("johnkeycloak", appRealm);

            GroupModel group14 =  KeycloakModelUtils.findGroupByPath(appRealm, "/group1/group14");
            GroupModel group3 =  KeycloakModelUtils.findGroupByPath(appRealm, "/group3");
            GroupModel group31 = KeycloakModelUtils.findGroupByPath(appRealm, "/group3/group31");
            GroupModel group32 = KeycloakModelUtils.findGroupByPath(appRealm, "/group3/group32");
            GroupModel group4 =  KeycloakModelUtils.findGroupByPath(appRealm, "/group4");

            Set<GroupModel> groups = john.getGroups();
            Assert.assertTrue(groups.contains(group14));
            Assert.assertFalse(groups.contains(group3));
            Assert.assertTrue(groups.contains(group31));
            Assert.assertTrue(groups.contains(group32));
            Assert.assertTrue(groups.contains(group4));
        });
    }

}
