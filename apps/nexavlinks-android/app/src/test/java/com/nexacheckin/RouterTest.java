package com.nexacheckin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RouterTest {
    private Account account(Site site, String id) {
        return new Account(1,"r","test","session=dummy",id,"{\"id\":"+id+"}","",0,site,"500000","test-agent");
    }
    private JSONObject user(String id) throws Exception {
        return new JSONObject().put("success",true).put("data",new JSONObject().put("id",id).put("quota",1000000).put("used_quota",500000));
    }
    @Test public void sameOriginChecksAreStrictAndDoNotAllowCredentialLeaks() {
        assertTrue(Site.AGENT.owns("https://agentrouter.org/console"));
        assertFalse(Site.AGENT.owns("http://agentrouter.org/"));
        assertFalse(Site.AGENT.owns("https://agentrouter.org.evil.test/"));
        assertFalse(Site.AGENT.owns("https://user@agentrouter.org/"));
        assertFalse(Site.AGENT.owns("https://agentrouter.org:444/"));
        assertFalse(Site.AGENT.owns(Site.ANY.origin));
    }
    @Test public void linuxDoAllowedOnlyForRouterLoginNotNormalSiteNavigation() {
        assertTrue(Site.AGENT.loginUrl("https://connect.linux.do/oauth2/authorize"));
        assertTrue(Site.ANY.loginUrl("https://linux.do/login"));
        assertFalse(Site.AGENT.owns("https://linux.do/login"));
        assertFalse(Site.NEXA.loginUrl("https://linux.do/login"));
        assertFalse(Site.ANY.loginUrl("https://github.com/login"));
    }
    @Test public void legacyAccountDefaultsToNexaAndRouterRoundTrips() throws Exception {
        Account a=account(Site.ANY,"123");JSONObject j=a.json();j.remove("site");j.remove("routerUnit");j.remove("userAgent");
        assertEquals(Site.NEXA,Account.from(j).site);
        Account restored=Account.from(new JSONObject(a.json().toString()));
        assertEquals(a.site,restored.site);assertEquals(a.routerUnit,restored.routerUnit);assertEquals(a.credential,restored.credential);
        assertEquals(a.site,a.renamed("new").site);assertEquals(a.routerUnit,a.renamed("new").routerUnit);
    }
    @Test public void providersWithSameNumericUserIdAreDifferentAccounts() throws Exception {
        Account old=account(Site.ANY,"123");
        Account second=new Account(2,"new","test","session=other","123","{\"id\":123}","",0,Site.AGENT,"500000","");
        LoginRequest login=LoginRequest.create(2,"test",null,Site.AGENT);
        assertEquals(2,login.accept(Collections.singletonList(old),login.nonce,second).size());
        assertNotEquals(old.identityKey(),second.identityKey());
    }
    @Test public void wrongProviderLoginAndRotationAreRejected() throws Exception {
        Account any=account(Site.ANY,"123"),agent=account(Site.AGENT,"123");
        LoginRequest login=LoginRequest.create(1,"test",null,Site.ANY);
        assertThrows(Exception.class,()->login.accept(Collections.emptyList(),login.nonce,agent));
        assertThrows(Exception.class,()->Renewal.replace(Collections.singletonList(any),any,agent));
        assertThrows(Exception.class,()->Renewal.renew(any,t->{fail("Nexa request");return null;},(a,b)->{},()->1));
    }
    @Test public void snapshotsAndReminderKeysAreProviderBound() throws Exception {
        Account any=account(Site.ANY,"123"),agent=account(Site.AGENT,"123");
        JSONArray json=SnapshotData.encode(Collections.singletonList(any),Collections.singletonMap(1,new Progress("2026-10-08")));
        assertTrue(SnapshotData.decode(Collections.singletonList(agent),json).isEmpty());
        assertNotEquals(ExpiryPolicy.key(any),ExpiryPolicy.key(agent));
        assertNotEquals(CheckinPresentation.detailKey(any),CheckinPresentation.detailKey(agent));
    }
    @Test public void httpSuccessAloneDoesNotProveAgentCheckin() throws Exception {
        Progress p=RouterApi.read(account(Site.AGENT,"123"),true,(a,claim)->{assertFalse(claim);return user("123");},"2026-10-08");
        assertNull(p.checked);assertTrue(p.warning.contains("未明确确认"));assertEquals("2.00000000",p.balance.toPlainString());
    }
    @Test public void agentExplicitRewardFlagConfirmsButFalseDoesNotMeanUnchecked() throws Exception {
        JSONObject u=user("123");u.getJSONObject("data").put("checked_in",true);
        Progress p=RouterApi.read(account(Site.AGENT,"123"),true,(a,claim)->u,"2026-10-08");
        assertEquals(Boolean.TRUE,p.checked);
        u.getJSONObject("data").put("checked_in",false);assertNull(RouterProtocol.checked(u.getJSONObject("data")));
    }
    @Test public void anyClaimOccursOnceAfterIdentityAndRechecksIdentity() throws Exception {
        AtomicInteger reads=new AtomicInteger(),posts=new AtomicInteger();
        Progress p=RouterApi.read(account(Site.ANY,"123"),true,(a,claim)->{
            if(claim){assertEquals(1,reads.get());posts.incrementAndGet();return new JSONObject().put("success",true);}
            reads.incrementAndGet();return user("123");
        },"2026-10-08");
        assertEquals(2,reads.get());assertEquals(1,posts.get());assertEquals(Boolean.TRUE,p.checked);
    }
    @Test public void identityMismatchStopsBeforeClaim() {
        AtomicInteger posts=new AtomicInteger();
        Progress p=RouterApi.read(account(Site.ANY,"123"),true,(a,claim)->{if(claim)posts.incrementAndGet();return user("456");},"2026-10-08");
        assertEquals(0,posts.get());assertNull(p.checked);assertNull(p.balance);
    }
    @Test public void postFailureIsNotRetriedOrGuessedFromMessage() throws Exception {
        AtomicInteger posts=new AtomicInteger();
        Progress p=RouterApi.read(account(Site.ANY,"123"),true,(a,claim)->{if(claim){posts.incrementAndGet();throw new Exception("timeout");}return user("123");},"2026-10-08");
        assertEquals(1,posts.get());assertNull(p.checked);
        assertFalse(RouterProtocol.claimConfirmed(new JSONObject().put("success","true").put("message","already signed")));
    }
    @Test public void conflictingPostAndQueryStayUnconfirmed() throws Exception {
        JSONObject u=user("123");u.getJSONObject("data").put("checked_in_today",false);
        Progress p=RouterApi.read(account(Site.ANY,"123"),true,(a,claim)->claim?new JSONObject().put("success",true):u,"2026-10-08");
        assertNull(p.checked);assertTrue(p.warning.contains("不一致"));
    }
    @Test public void missingConversionUnitDoesNotFabricateBalance() throws Exception {
        Account a=new Account(1,"r","test","session=dummy","123","{\"id\":123}","",0,Site.ANY,"","");
        Progress p=RouterProtocol.snapshot(a,user("123").getJSONObject("data"),"2026-10-08");
        assertNull(p.balance);assertNull(p.totalCost);
    }
    @Test public void routerCannotReachNexaReadEndpoints() {
        Progress p=Progress.read(account(Site.ANY,"123"),(path,token)->{fail("Nexa endpoint invoked");return null;},()->"2026-10-08");
        assertFalse(p.warning.isEmpty());
    }
}
