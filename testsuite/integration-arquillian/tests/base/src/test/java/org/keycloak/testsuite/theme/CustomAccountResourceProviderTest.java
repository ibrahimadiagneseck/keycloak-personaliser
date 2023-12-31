package org.keycloak.testsuite.theme;

import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.resource.AccountResourceProvider;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.theme.CustomAccountResourceProviderFactory;
import org.keycloak.theme.Theme;

public class CustomAccountResourceProviderTest extends AbstractTestRealmKeycloakTest {

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {

    }

    @Test
    public void testProviderOverride() {
        testingClient.server().run(session -> {
            AccountResourceProvider arp = session.getProvider(AccountResourceProvider.class, "ext-custom-account-console");
            Assert.assertTrue(arp instanceof CustomAccountResourceProviderFactory);
        });
    }

}
