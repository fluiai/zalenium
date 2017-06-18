package de.zalando.ep.zalenium.proxy;


import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.zalando.ep.zalenium.util.CommonProxyUtilities;
import de.zalando.ep.zalenium.util.Environment;
import de.zalando.ep.zalenium.util.TestUtils;
import de.zalando.ep.zalenium.dashboard.TestInformation;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

import static org.mockito.Mockito.*;

public class BrowserStackRemoteProxyTest {

    private BrowserStackRemoteProxy browserStackProxy;
    private Registry registry;

    @SuppressWarnings("ConstantConditions")
    @Before
    public void setUp() {
        registry = Registry.newInstance();
        // Creating the configuration and the registration request of the proxy (node)
        RegistrationRequest request = TestUtils.getRegistrationRequestForTesting(30002,
                BrowserStackRemoteProxy.class.getCanonicalName());
        CommonProxyUtilities commonProxyUtilities = mock(CommonProxyUtilities.class);
        when(commonProxyUtilities.readJSONFromUrl(anyString(), anyString(), anyString())).thenReturn(null);
        BrowserStackRemoteProxy.setCommonProxyUtilities(commonProxyUtilities);
        browserStackProxy = BrowserStackRemoteProxy.getNewInstance(request, registry);

        // we need to register a DockerSeleniumStarter proxy to have a proper functioning BrowserStackProxy
        request = TestUtils.getRegistrationRequestForTesting(30000,
                DockerSeleniumStarterRemoteProxy.class.getCanonicalName());
        DockerSeleniumStarterRemoteProxy dsStarterProxy = DockerSeleniumStarterRemoteProxy.getNewInstance(request, registry);

        // We add both nodes to the registry
        registry.add(browserStackProxy);
        registry.add(dsStarterProxy);
    }

    @After
    public void tearDown() {
        BrowserStackRemoteProxy.restoreCommonProxyUtilities();
        BrowserStackRemoteProxy.restoreGa();
        BrowserStackRemoteProxy.restoreEnvironment();
    }

    @Test
    public void checkProxyOrdering() {
        // Checking that the DockerSeleniumStarterProxy should come before SauceLabsProxy
        List<RemoteProxy> sorted = registry.getAllProxies().getSorted();
        Assert.assertEquals(2, sorted.size());
        Assert.assertEquals(DockerSeleniumStarterRemoteProxy.class, sorted.get(0).getClass());
        Assert.assertEquals(BrowserStackRemoteProxy.class, sorted.get(1).getClass());
    }

    @Test
    public void sessionIsCreatedWithCapabilitiesThatDockerSeleniumCannotProcess() {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.SAFARI);
        requestedCapability.put(CapabilityType.PLATFORM, Platform.MAC);

        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);

        Assert.assertNotNull(testSession);
    }

    @Test
    public void credentialsAreAddedInSessionCreation() throws IOException {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.IE);
        requestedCapability.put(CapabilityType.PLATFORM, Platform.WIN8);

        // Getting a test session in the sauce labs node
        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(testSession);

        // We need to mock all the needed objects to forward the session and see how in the beforeMethod
        // the SauceLabs user and api key get added to the body request.
        WebDriverRequest request = TestUtils.getMockedWebDriverRequestStartSession();

        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream stream = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(stream);

        testSession.forward(request, response, true);

        Environment env = new Environment();

        // The body should now have the BrowserStack variables
        String expectedBody = String.format("{\"desiredCapabilities\":{\"browserName\":\"internet explorer\",\"platform\":" +
                                "\"WIN8\",\"browserstack.user\":\"%s\",\"browserstack.key\":\"%s\"}}",
                        env.getStringEnvVariable("BROWSER_STACK_USER", ""),
                        env.getStringEnvVariable("BROWSER_STACK_KEY", ""));
        verify(request).setBody(expectedBody);
    }

    @Test
    public void testInformationIsRetrievedWhenStoppingSession() throws IOException {
        // Capability which should result in a created session
        try {
            Map<String, Object> requestedCapability = new HashMap<>();
            requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.CHROME);
            requestedCapability.put(CapabilityType.PLATFORM, Platform.WIN10);

            JsonElement informationSample = TestUtils.getTestInformationSample("browserstack_testinformation.json");
            CommonProxyUtilities commonProxyUtilities = mock(CommonProxyUtilities.class);
            Environment env = new Environment();
            String mockTestInformationUrl = "https://www.browserstack.com/automate/sessions/77e51cead8e6e37b0a0feb0dfa69325b2c4acf97.json";
            when(commonProxyUtilities.readJSONFromUrl(mockTestInformationUrl,
                    env.getStringEnvVariable("BROWSER_STACK_USER", ""),
                    env.getStringEnvVariable("BROWSER_STACK_KEY", ""))).thenReturn(informationSample);
            BrowserStackRemoteProxy.setCommonProxyUtilities(commonProxyUtilities);

            // Getting a test session in the sauce labs node
            BrowserStackRemoteProxy bsSpyProxy = spy(browserStackProxy);
            TestSession testSession = bsSpyProxy.getNewSession(requestedCapability);
            Assert.assertNotNull(testSession);
            String mockSeleniumSessionId = "77e51cead8e6e37b0a0feb0dfa69325b2c4acf97";
            testSession.setExternalKey(new ExternalSessionKey(mockSeleniumSessionId));

            // We release the session, the node should be free
            WebDriverRequest request = mock(WebDriverRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(request.getMethod()).thenReturn("DELETE");
            when(request.getRequestType()).thenReturn(RequestType.STOP_SESSION);
            testSession.getSlot().doFinishRelease();
            bsSpyProxy.afterCommand(testSession, request, response);

            verify(bsSpyProxy, timeout(1000 * 5)).getTestInformation(mockSeleniumSessionId);
            TestInformation testInformation = bsSpyProxy.getTestInformation(mockSeleniumSessionId);
            Assert.assertEquals("loadZalandoPageAndCheckTitle", testInformation.getTestName());
            Assert.assertThat(testInformation.getFileName(),
                    CoreMatchers.containsString("browserstack_loadZalandoPageAndCheckTitle_safari_OS_X"));
            Assert.assertEquals("safari 6.2, OS X Mountain Lion", testInformation.getBrowserAndPlatform());
            Assert.assertEquals("https://www.browserstack.com/s3-upload/bs-video-logs-use/s3/77e51cead8e6e37b0" +
                            "a0feb0dfa69325b2c4acf97/video-77e51cead8e6e37b0a0feb0dfa69325b2c4acf97.mp4?AWSAccessKeyId=" +
                            "AKIAIOW7IEY5D4X2OFIA&Expires=1497088589&Signature=tQ9SCH1lgg6FjlBIhlTDwummLWc%3D&response-" +
                            "content-type=video%2Fmp4",
                    testInformation.getVideoUrl());

        } finally {
            BrowserStackRemoteProxy.restoreCommonProxyUtilities();
            BrowserStackRemoteProxy.restoreGa();
            BrowserStackRemoteProxy.restoreEnvironment();
        }
    }

    @Test
    public void requestIsNotModifiedInOtherRequestTypes() throws IOException {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.IE);
        requestedCapability.put(CapabilityType.PLATFORM, Platform.WIN8);

        // Getting a test session in the sauce labs node
        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(testSession);

        // We need to mock all the needed objects to forward the session and see how in the beforeMethod
        // the SauceLabs user and api key get added to the body request.
        WebDriverRequest request = mock(WebDriverRequest.class);
        when(request.getRequestURI()).thenReturn("session");
        when(request.getServletPath()).thenReturn("session");
        when(request.getContextPath()).thenReturn("");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestType()).thenReturn(RequestType.REGULAR);
        JsonObject jsonObject = new JsonObject();
        JsonObject desiredCapabilities = new JsonObject();
        desiredCapabilities.addProperty(CapabilityType.BROWSER_NAME, BrowserType.IE);
        desiredCapabilities.addProperty(CapabilityType.PLATFORM, Platform.WIN8.name());
        jsonObject.add("desiredCapabilities", desiredCapabilities);
        when(request.getBody()).thenReturn(jsonObject.toString());

        Enumeration<String> strings = Collections.emptyEnumeration();
        when(request.getHeaderNames()).thenReturn(strings);

        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream stream = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(stream);

        testSession.forward(request, response, true);

        // The body should not be affected and not contain the BrowserStack variables
        Assert.assertThat(request.getBody(), CoreMatchers.containsString(jsonObject.toString()));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.user")));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.key")));

        when(request.getMethod()).thenReturn("GET");

        testSession.forward(request, response, true);
        Assert.assertThat(request.getBody(), CoreMatchers.containsString(jsonObject.toString()));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.user")));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.key")));
    }

    @Test
    public void checkVideoFileExtensionAndProxyName() {
        Assert.assertEquals(".mp4", browserStackProxy.getVideoFileExtension());
        Assert.assertEquals("BrowserStack", browserStackProxy.getProxyName());
    }
}