/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.testsuite.forms;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.keycloak.testsuite.util.ServerURLs.getAuthServerContextRoot;
import static org.keycloak.testsuite.util.URLAssert.assertCurrentUrlStartsWith;

import java.net.MalformedURLException;
import java.net.URL;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.arquillian.graphene.page.Page;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.models.Constants;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.ActionURIUtils;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.InfoPage;
import org.keycloak.testsuite.pages.LoginExpiredPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.LoginPasswordResetPage;
import org.keycloak.testsuite.pages.LoginPasswordUpdatePage;
import org.keycloak.testsuite.pages.LoginUpdateProfilePage;
import org.keycloak.testsuite.pages.OAuthGrantPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.pages.VerifyEmailPage;
import org.keycloak.testsuite.util.BrowserTabUtil;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.UserBuilder;
import org.keycloak.testsuite.util.WaitUtils;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

/**
 * Tries to simulate testing with multiple browser tabs
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class MultipleTabsLoginTest extends AbstractTestRealmKeycloakTest {

    private String userId;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }

    @Override
    protected boolean modifyRealmForSSL() {
        return true;
    }

    @Before
    public void setup() {
        UserRepresentation user = UserBuilder.create()
                .username("login-test")
                .email("login@test.com")
                .enabled(true)
                .requiredAction(UserModel.RequiredAction.UPDATE_PROFILE.toString())
                .requiredAction(UserModel.RequiredAction.UPDATE_PASSWORD.toString())
                .build();

        userId = ApiUtil.createUserAndResetPasswordWithAdminClient(testRealm(), user, "password", true);
        getCleanup().addUserId(userId);

        oauth.clientId("test-app");
    }

    @Rule
    public GreenMailRule greenMail = new GreenMailRule();

    @Page
    protected AppPage appPage;

    @Page
    protected LoginPage loginPage;

    @Page
    protected ErrorPage errorPage;

    @Page
    protected InfoPage infoPage;

    @Page
    protected VerifyEmailPage verifyEmailPage;

    @Page
    protected LoginPasswordResetPage resetPasswordPage;

    @Page
    protected LoginPasswordUpdatePage updatePasswordPage;

    @Page
    protected LoginUpdateProfilePage updateProfilePage;

    @Page
    protected LoginExpiredPage loginExpiredPage;

    @Page
    protected RegisterPage registerPage;

    @Page
    protected OAuthGrantPage grantPage;

    @Rule
    public AssertEvents events = new AssertEvents(this);


    @Test
    public void multipleTabsParallelLoginTest() {
        try (BrowserTabUtil tabUtil = BrowserTabUtil.getInstanceAndSetEnv(driver)) {
            assertThat(tabUtil.getCountOfTabs(), Matchers.is(1));
            oauth.openLoginForm();
            loginPage.assertCurrent();

            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();

            // Simulate login in different browser tab tab2. I will be on loginPage again.
            tabUtil.newTab(oauth.getLoginFormUrl());
            assertThat(tabUtil.getCountOfTabs(), Matchers.equalTo(2));

            oauth.openLoginForm();
            loginPage.assertCurrent();

            // Login in tab2
            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();

            updatePasswordPage.changePassword("password", "password");
            updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                    .email("john@doe3.com").submit();
            appPage.assertCurrent();

            // Try to go back to tab 1. We should be logged-in automatically
            tabUtil.closeTab(1);
            assertThat(tabUtil.getCountOfTabs(), Matchers.equalTo(1));

            // Should be back on tab1
            if (driver instanceof HtmlUnitDriver) {
                driver.navigate().refresh(); // Need to explicitly refresh with HtmlUnitDriver due the authChecker.js javascript does not work
            }

            // Should be back on tab1 and logged-in automatically here
            WaitUtils.waitUntilElement(appPage.getAccountLink()).is().clickable();
            appPage.assertCurrent();
        }
    }

    @Test
    public void testLoginAfterLogoutFromDifferentTab() {
        try (BrowserTabUtil util = BrowserTabUtil.getInstanceAndSetEnv(driver)) {
            // login in the first tab
            oauth.openLoginForm();
            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();
            String tab1WindowHandle = util.getActualWindowHandle();
            updatePasswordPage.changePassword("password", "password");
            updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                    .email("john@doe3.com").submit();
            appPage.assertCurrent();
            String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);
            OAuthClient.AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(code, "password");
            AccessToken accessToken = oauth.verifyToken(tokenResponse.getAccessToken());

            // seamless login in the second tab, user already authenticated
            util.newTab(oauth.getLoginFormUrl());
            oauth.openLoginForm();
            appPage.assertCurrent();
            events.clear();
            // logout in the second tab
            oauth.idTokenHint(tokenResponse.getIdToken()).openLogout();
            events.expectLogout(accessToken.getSessionState()).user(userId).session(accessToken.getSessionState()).assertEvent();
            // re-login in the second tab
            oauth.openLoginForm();
            loginPage.login("login-test", "password");
            appPage.assertCurrent();

            // seamless authentication in the first tab
            util.switchToTab(tab1WindowHandle);
            oauth.openLoginForm();
            appPage.assertCurrent();
        }
    }

    @Test
    public void multipleTabsLoginAndPassiveCheck() throws MalformedURLException {
        try (BrowserTabUtil util = BrowserTabUtil.getInstanceAndSetEnv(driver)) {
            oauth.openLoginForm();
            loginPage.assertCurrent();
            String originalTab = util.getActualWindowHandle();

            // open a new tab performing the passive check
            String passiveCheckUrl = oauth.responseType("none").prompt("none").getLoginFormUrl();
            util.newTab(passiveCheckUrl);
            MatcherAssert.assertThat(new URL(oauth.getDriver().getCurrentUrl()).getQuery(), Matchers.containsString("error=login_required"));

            // continue with the login in the first tab
            util.switchToTab(originalTab);
            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();
        }
    }


    @Test
    public void expiredAuthenticationAction_currentCodeExpiredExecution() {
        // Simulate to open login form in 2 tabs
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String actionUrl1 = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());

        // Click "register" in tab2
        loginPage.clickRegister();
        registerPage.assertCurrent();

        // Simulate going back to tab1 and confirm login form. Page "showExpired" should be shown (NOTE: WebDriver does it with GET, when real browser would do it with POST. Improve test if needed...)
        driver.navigate().to(actionUrl1);
        loginExpiredPage.assertCurrent();

        // Click on continue and assert I am on "register" form
        loginExpiredPage.clickLoginContinueLink();
        registerPage.assertCurrent();

        // Finally click "Back to login" and authenticate
        registerPage.clickBackToLogin();
        loginPage.assertCurrent();

        // Login success now
        loginPage.login("login-test", "password");
        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();
        appPage.assertCurrent();
    }


    @Test
    public void expiredAuthenticationAction_expiredCodeCurrentExecution() {
        // Simulate to open login form in 2 tabs
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String actionUrl1 = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());

        loginPage.login("invalid", "invalid");
        loginPage.assertCurrent();
        Assert.assertEquals("Invalid username or password.", loginPage.getInputError());

        // Simulate going back to tab1 and confirm login form. Login page with "action expired" message should be shown (NOTE: WebDriver does it with GET, when real browser would do it with POST. Improve test if needed...)
        driver.navigate().to(actionUrl1);
        loginPage.assertCurrent();
        Assert.assertEquals("Action expired. Please continue with login now.", loginPage.getError());

        // Login success now
        loginPage.login("login-test", "password");
        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();
        appPage.assertCurrent();
    }


    @Test
    public void expiredAuthenticationAction_expiredCodeExpiredExecution() {
        // Open tab1
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String actionUrl1 = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());

        // Authenticate in tab2
        loginPage.login("login-test", "password");
        updatePasswordPage.assertCurrent();

        // Simulate going back to tab1 and confirm login form. Page "Page expired" should be shown (NOTE: WebDriver does it with GET, when real browser would do it with POST. Improve test if needed...)
        driver.navigate().to(actionUrl1);
        loginExpiredPage.assertCurrent();

        // Finish login
        loginExpiredPage.clickLoginContinueLink();
        updatePasswordPage.assertCurrent();

        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();
        appPage.assertCurrent();
    }


    @Test
    public void loginActionWithoutExecution() throws Exception {
        oauth.openLoginForm();

        // Manually remove execution from the URL and try to simulate the request just with "code" parameter
        String actionUrl = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());
        actionUrl = ActionURIUtils.removeQueryParamFromURI(actionUrl, Constants.EXECUTION);

        driver.navigate().to(actionUrl);

        loginExpiredPage.assertCurrent();
    }


    // Same like "loginActionWithoutExecution", but AuthenticationSession is in REQUIRED_ACTIONS action
    @Test
    public void loginActionWithoutExecutionInRequiredActions() throws Exception {
        oauth.openLoginForm();
        loginPage.assertCurrent();

        loginPage.login("login-test", "password");
        updatePasswordPage.assertCurrent();

        // Manually remove execution from the URL and try to simulate the request just with "code" parameter
        String actionUrl = ActionURIUtils.getActionURIFromPageSource(driver.getPageSource());
        actionUrl = ActionURIUtils.removeQueryParamFromURI(actionUrl, Constants.EXECUTION);

        driver.navigate().to(actionUrl);

        // Back on updatePasswordPage now
        updatePasswordPage.assertCurrent();

        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();
        appPage.assertCurrent();
    }


    // KEYCLOAK-5797
    @Test
    public void loginWithDifferentClients() throws Exception {
       String redirectUri = String.format("%s/foo/bar/baz", getAuthServerContextRoot());
       // Open tab1 and start login here
       oauth.openLoginForm();
       loginPage.assertCurrent();
       loginPage.login("login-test", "bad-password");
       String tab1Url = driver.getCurrentUrl();

       // Go to tab2 and start login with different client "root-url-client"
       oauth.clientId("root-url-client");
        oauth.redirectUri(redirectUri);
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String tab2Url = driver.getCurrentUrl();

        // Go back to tab1 and finish login here
        driver.navigate().to(tab1Url);
        loginPage.login("login-test", "password");
        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();

        // Assert I am redirected to the appPage in tab1
        appPage.assertCurrent();

        // Go back to tab2 and finish login here. Should be on the root-url-client page
        driver.navigate().to(tab2Url);
        assertCurrentUrlStartsWith(redirectUri);
    }


    // KEYCLOAK-5938
    @Test
    public void loginWithSameClientDifferentStatesLoginInTab1() throws Exception {
        String redirectUri1 = String.format("%s/auth/realms/master/app/auth/suffix1", getAuthServerContextRoot());
        String redirectUri2 = String.format("%s/auth/realms/master/app/auth/suffix2", getAuthServerContextRoot());
        // Open tab1 and start login here
        oauth.stateParamHardcoded("state1");
        oauth.redirectUri(redirectUri1);
        oauth.openLoginForm();
        loginPage.assertCurrent();
        loginPage.login("login-test", "bad-password");
        String tab1Url = driver.getCurrentUrl();

        // Go to tab2 and start login with different client "root-url-client"
        oauth.stateParamHardcoded("state2");
        oauth.redirectUri(redirectUri2);
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String tab2Url = driver.getCurrentUrl();

        // Go back to tab1 and finish login here
        driver.navigate().to(tab1Url);
        loginPage.login("login-test", "password");
        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();

        // Assert I am redirected to the appPage in tab1 and have state corresponding to tab1
        appPage.assertCurrent();
        String currentUrl = driver.getCurrentUrl();
        assertCurrentUrlStartsWith(redirectUri1);
        Assert.assertTrue(currentUrl.contains("state1"));
    }


    // KEYCLOAK-5938
    @Test
    public void loginWithSameClientDifferentStatesLoginInTab2() throws Exception {
        String redirectUri1 = String.format("%s/auth/realms/master/app/auth/suffix1", getAuthServerContextRoot());
        String redirectUri2 = String.format("%s/auth/realms/master/app/auth/suffix2", getAuthServerContextRoot());
        // Open tab1 and start login here
        oauth.stateParamHardcoded("state1");
        oauth.redirectUri(redirectUri1);
        oauth.openLoginForm();
        loginPage.assertCurrent();
        loginPage.login("login-test", "bad-password");
        String tab1Url = driver.getCurrentUrl();

        // Go to tab2 and start login with different client "root-url-client"
        oauth.stateParamHardcoded("state2");
        oauth.redirectUri(redirectUri2);
        oauth.openLoginForm();
        loginPage.assertCurrent();
        String tab2Url = driver.getCurrentUrl();

        // Continue in tab2 and finish login here
        loginPage.login("login-test", "password");
        updatePasswordPage.changePassword("password", "password");
        updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                .email("john@doe3.com").submit();

        // Assert I am redirected to the appPage in tab2 and have state corresponding to tab2
        appPage.assertCurrent();
        String currentUrl = driver.getCurrentUrl();
        assertCurrentUrlStartsWith(redirectUri2);
        Assert.assertTrue(currentUrl.contains("state2"));
    }

    // KEYCLOAK-12161
    @Test
    public void testEmptyBaseUrl() throws Exception {
        try (BrowserTabUtil tabUtil = BrowserTabUtil.getInstanceAndSetEnv(driver)) {
            assertThat(tabUtil.getCountOfTabs(), Matchers.is(1));

            String clientUuid = KeycloakModelUtils.generateId();
            ClientRepresentation emptyBaseclient = ClientBuilder.create()
                    .clientId("empty-baseurl-client")
                    .id(clientUuid)
                    .enabled(true)
                    .baseUrl("")
                    .addRedirectUri("*")
                    .secret("password")
                    .build();
            testRealm().clients().create(emptyBaseclient);
            getCleanup().addClientUuid(clientUuid);

            oauth.clientId("empty-baseurl-client");
            oauth.openLoginForm();
            loginPage.assertCurrent();

            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();

            String tab1Url = driver.getCurrentUrl();

            // Simulate login in different browser tab tab2. I will be on loginPage again.
            tabUtil.newTab(oauth.getLoginFormUrl());
            assertThat(tabUtil.getCountOfTabs(), Matchers.equalTo(2));

            loginPage.assertCurrent();

            // Login in tab2
            loginPage.login("login-test", "password");
            updatePasswordPage.assertCurrent();

            updatePasswordPage.changePassword("password", "password");
            updateProfilePage.prepareUpdate().firstName("John").lastName("Doe3")
                    .email("john@doe3.com").submit();
            appPage.assertCurrent();

            // Try to go back to tab 1. We should be logged-in automatically
            tabUtil.closeTab(1);
            assertThat(tabUtil.getCountOfTabs(), Matchers.equalTo(1));

            if (driver instanceof HtmlUnitDriver) {
                driver.navigate().refresh(); // Need to explicitly refresh with HtmlUnitDriver due the authChecker.js javascript does not work
            }

            // Should be back on tab1 and logged-in automatically here
            WaitUtils.waitUntilElement(appPage.getAccountLink()).is().clickable();
            appPage.assertCurrent();
        }
    }
}
