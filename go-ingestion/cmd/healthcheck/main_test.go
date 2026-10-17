package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestProbe(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusServiceUnavailable} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(status)
			}))
			defer server.Close()
			err := probe(server.Client(), server.URL)
			if (err == nil) != (status == http.StatusOK) {
				t.Fatalf("status=%d, error=%v", status, err)
			}
		})
	}
}

func TestProbeUnavailable(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	endpoint := server.URL
	server.Close()
	if err := probe(&http.Client{Timeout: time.Second}, endpoint); err == nil {
		t.Fatal("expected an error for an unavailable server")
	}
}
