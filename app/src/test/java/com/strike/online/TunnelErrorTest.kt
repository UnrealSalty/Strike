package com.strike.online

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TunnelErrorTest {
    @Test fun fatalDetailsSurviveJsonOutput() {
        assertEquals("The tunnel's local port is already in use", tunnelError(error(
            "listen tcp 127.0.0.1:19889: bind: address already in use")))
        assertEquals("Cloudflare could not be resolved. Check the car's connection", tunnelError(error(
            "lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: connection refused")))
        assertEquals("Tunnel: Unable to establish connection with Cloudflare edge",
            tunnelError(error("Unable to establish connection with Cloudflare edge")))
    }

    @Test fun shutdownMessagesDoNotReplaceTheCause() {
        assertNull(tunnelError(error("context canceled")))
        assertNull(tunnelError(JSONObject().put("level", "error")
            .put("message", "Initiating shutdown").toString()))
        assertNull(tunnelError(JSONObject().put("level", "info")
            .put("message", "Tunnel server stopped").toString()))
    }

    @Test fun unrecognisedErrorsAreBoundedAndCredentialsAreRedacted() {
        val token = sampleTunnelToken()
        val reply = tunnelError(error("Rejected $token " + "x".repeat(500)), token)!!
        assertFalse(reply.contains(token))
        assertTrue(reply.length <= 248)
        assertEquals("Cloudflare rejected the token. Update the setup",
            tunnelError("Invalid secret: short-credential"))
    }

    @Test fun plainStartupErrorsRemainVisible() {
        assertEquals("Android denied the tunnel access", tunnelError("bind: permission denied"))
        assertEquals("Tunnel: flag provided but not defined: -unexpected",
            tunnelError("flag provided but not defined: -unexpected"))
    }

    @Test fun theDecodedCredentialIsAlsoRedacted() {
        val secret = "short-sensitive-value"
        val credentials = JSONObject().put("a", "account").put("t", "tunnel").put("s", secret)
        val token = Base64.getEncoder().encodeToString(credentials.toString().toByteArray())
        assertEquals("Tunnel: Peer refused [redacted]", tunnelError(error("Peer refused $secret"), token))
    }

    private fun error(reason: String) = JSONObject().put("level", "error")
        .put("message", "Tunnel failed").put("error", reason).toString()
}
