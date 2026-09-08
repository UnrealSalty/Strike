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
	var next atomic.Uint32
	resolver := &net.Resolver{Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		server := servers[(next.Add(1)-1)%uint32(len(servers))]
		dialer := &net.Dialer{Timeout: 5 * time.Second}
		if privateDNS {
			if serverName != "" {
				server = serverName
			}
			secure := &tls.Dialer{NetDialer: dialer}
			return secure.DialContext(ctx, "tcp", net.JoinHostPort(server, "853"))
		}
		return dialer.DialContext(ctx, network, net.JoinHostPort(server, "53"))
	}}
	// Go's SRV resolver reads resolv.conf, which Android does not provide.
	netLookupSRV = func(service, proto, name string) (string, []*net.SRV, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return resolver.LookupSRV(ctx, service, proto, name)
	}
}
