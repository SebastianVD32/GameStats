package main

import (
	"fmt"
	"net/http"
	"os"
	"time"
)

func probe(client *http.Client, endpoint string) error {
	response, err := client.Get(endpoint)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("health endpoint returned %d", response.StatusCode)
	}
	return nil
}

func main() {
	endpoint := "http://127.0.0.1:8080/healthz"
	if len(os.Args) == 2 {
		endpoint = os.Args[1]
	}
	if err := probe(&http.Client{Timeout: 2 * time.Second}, endpoint); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
