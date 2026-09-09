package allregions

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http/httptest"
	"testing"
	"time"
)

// Run standalone with: go test dns_android.go dns_android_test.go
var netLookupSRV = net.LookupSRV

func TestPrivateDNS(t *testing.T) {
	certificate := httptest.NewTLSServer(nil)
	defer certificate.Close()
	listener, err := tls.Listen("tcp", "127.0.0.1:853", certificate.TLS)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			conn.(*tls.Conn).Handshake()
			conn.Close()
		}
	}()

	t.Run("automatic mode encrypts without a provider hostname", func(t *testing.T) {
		resolver := androidResolver([]string{"127.0.0.1"}, true, "")
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		conn, err := resolver.Dial(ctx, "udp", "")
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		secure, ok := conn.(*tls.Conn)
		if !ok || !secure.ConnectionState().HandshakeComplete {
			t.Fatal("DNS connection is not encrypted")
		}
	})

	t.Run("named provider still requires a valid certificate", func(t *testing.T) {
		resolver := androidResolver([]string{"127.0.0.1"}, true, "localhost")
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		conn, err := resolver.Dial(ctx, "udp", "")
		if conn != nil {
			conn.Close()
			t.Fatal("Strict DNS accepted an untrusted certificate")
		}
		var certificateError *tls.CertificateVerificationError
		if !errors.As(err, &certificateError) {
			t.Fatalf("Expected certificate rejection, got %v", err)
		}
	})
}

func TestOrdinaryDNS(t *testing.T) {
	resolver := androidResolver([]string{"127.0.0.1", "127.0.0.2"}, false, "")
	for _, address := range []string{"127.0.0.1:53", "127.0.0.2:53", "127.0.0.1:53"} {
		conn, err := resolver.Dial(context.Background(), "udp", "")
		if err != nil {
			t.Fatal(err)
		}
		conn.Close()
		if _, ok := conn.(*net.UDPConn); !ok || conn.RemoteAddr().String() != address {
			t.Fatalf("Expected UDP DNS at %s, got %v", address, conn.RemoteAddr())
		}
	}
}
