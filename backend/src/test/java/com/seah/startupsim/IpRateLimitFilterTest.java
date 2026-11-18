package com.seah.startupsim;

import com.seah.startupsim.common.IpRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class IpRateLimitFilterTest {
    private MockHttpServletResponse send(IpRateLimitFilter filter,String path,String ip,String forwarded) throws Exception {
        var request=new MockHttpServletRequest("POST",path);request.setRemoteAddr(ip);
        if(forwarded!=null)request.addHeader("X-Forwarded-For",forwarded);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(201));
        return response;
    }
    @Test void limitsSharedIpAcrossConfiguredAuthPathsAndIgnoresSpoofedProxy() throws Exception {
        var filter=new IpRateLimitFilter("/api/guest,/api/auth/signup,/api/auth/login",2,600,false,100);
        assertEquals(201,send(filter,"/api/guest","1.2.3.4","fake1").getStatus());
        assertEquals(201,send(filter,"/api/auth/signup","1.2.3.4","fake2").getStatus());
        var limited=send(filter,"/api/auth/login","1.2.3.4","fake3");
        assertEquals(429,limited.getStatus());assertTrue(limited.getContentAsString().contains("RATE_LIMITED"));
        assertTrue(limited.getContentAsString().contains("timestamp"));
        assertEquals(201,send(filter,"/api/guest","5.6.7.8",null).getStatus());
        assertEquals(201,send(filter,"/api/other","1.2.3.4",null).getStatus());
    }
    @Test void trustedProxySeparatesForwardedClientsAndMemoryIsBounded() throws Exception {
        var filter=new IpRateLimitFilter("/api/guest",1,600,true,2);
        assertEquals(201,send(filter,"/api/guest","proxy","client1, proxy").getStatus());
        assertEquals(429,send(filter,"/api/guest","proxy","client1").getStatus());
        assertEquals(201,send(filter,"/api/guest","proxy","client2").getStatus());
        assertEquals(429,send(filter,"/api/guest","proxy","client3").getStatus());
    }
    @Test void windowExpires() throws Exception {
        var filter=new IpRateLimitFilter("/api/guest",1,1,false,2);
        assertEquals(201,send(filter,"/api/guest","client",null).getStatus());
        assertEquals(429,send(filter,"/api/guest","client",null).getStatus());
        Thread.sleep(1050);
        assertEquals(201,send(filter,"/api/guest","client",null).getStatus());
    }
    @Test void allConfiguredSecretsAreMaskedIncludingEncodedValues() {
        var masker=new com.seah.startupsim.common.SecretMasker(new com.seah.startupsim.common.ExternalProperties());
        for(String field:java.util.List.of("adminPassword","databasePassword","kakaoClientSecret","kakaoConfiguredSecret")) {
            org.springframework.test.util.ReflectionTestUtils.setField(masker,field,"fixture-private +");
            assertEquals("**** ****",masker.mask("fixture-private + fixture-private+%2B"));
        }
    }
}
