package allregions

import (
	"context"
	"crypto/tls"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

func init() {
	servers := strings.Fields(os.Getenv("STRIKE_DNS_SERVERS"))
	if len(servers) == 0 {
		return
	}
	privateDNS := os.Getenv("STRIKE_DNS_TLS") == "1"
	serverName := os.Getenv("STRIKE_DNS_NAME")
	resolver := androidResolver(servers, privateDNS, serverName)
	// Go's SRV resolver reads resolv.conf, which Android does not provide.
	netLookupSRV = func(service, proto, name string) (string, []*net.SRV, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return resolver.LookupSRV(ctx, service, proto, name)
	}
}

func androidResolver(servers []string, privateDNS bool, serverName string) *net.Resolver {
	var next atomic.Uint32
	return &net.Resolver{Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		server := servers[(next.Add(1)-1)%uint32(len(servers))]
		dialer := &net.Dialer{Timeout: 5 * time.Second}
		if privateDNS {
			if serverName != "" {
				server = serverName
			}
			// Automatic Private DNS encrypts without a configured peer identity.
			secure := &tls.Dialer{NetDialer: dialer, Config: &tls.Config{InsecureSkipVerify: serverName == ""}}
			return secure.DialContext(ctx, "tcp", net.JoinHostPort(server, "853"))
		}
		return dialer.DialContext(ctx, network, net.JoinHostPort(server, "53"))
	}}
}
