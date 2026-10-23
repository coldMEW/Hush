package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RulesTest {
    @Test public void defaultsAreValid() {
        assertNull(Rules.validate(Rules.DEFAULTS));
    }

    @Test public void rejectsBadLines() {
        assertNotNull(Rules.validate("drop example.com"));
        assertNotNull(Rules.validate("block"));
        assertNotNull(Rules.validate("redirect example.com"));
        assertNotNull(Rules.validate("ip example.com 300.1.1.1"));
        assertNotNull(Rules.validate("upstream dns.google"));
        assertNotNull(Rules.validate("block not_a_domain"));
        assertEquals("2: missing domain", Rules.validate("block a.com" + (char) 10 + "block" + (char) 10));
    }

    @Test public void domains() {
        assertTrue(Rules.isDomain("adstudio-assets.scdn.co"));
        assertFalse(Rules.isDomain("a..b.com"));
        assertFalse(Rules.isDomain("$(id).com"));
        assertNotNull(Rules.validate("redirect a.com not_a_host"));
        assertNull(Rules.validate("redirect a.com b.example.org"));
    }

    @Test public void ipv4() {
        assertTrue(Rules.isIpv4("1.1.1.1"));
        assertTrue(Rules.isIpv4("255.255.255.0"));
        assertFalse(Rules.isIpv4("256.1.1.1"));
        assertFalse(Rules.isIpv4("1.1.1"));
        assertFalse(Rules.isIpv4("1.1.1.1.1"));
        assertFalse(Rules.isIpv4("a.b.c.d"));
    }
}
