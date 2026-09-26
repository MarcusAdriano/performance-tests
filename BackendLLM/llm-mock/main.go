package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
)

func main() {
	cfg, err := ConfigFromEnv(os.Getenv)
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	registry := prometheus.NewRegistry()
	registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           NewServer(cfg, registry).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("llm-mock listening on :%s (delay %s..%s, error rate %.2f)", cfg.Port, cfg.MinDelay, cfg.MaxDelay, cfg.ErrorRate)
	log.Fatal(srv.ListenAndServe())
}
