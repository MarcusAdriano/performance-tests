package main

import (
	"testing"
	"time"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestConfigDefaults(t *testing.T) {
	cfg, err := ConfigFromEnv(env(nil))
	if err != nil {
		t.Fatal(err)
	}
	want := Config{Port: "9000", MinDelay: 2 * time.Second, MaxDelay: 5 * time.Second, ErrorRate: 0}
	if cfg != want {
		t.Fatalf("cfg = %+v, want %+v", cfg, want)
	}
}

func TestConfigFromEnvOverrides(t *testing.T) {
	cfg, err := ConfigFromEnv(env(map[string]string{"PORT": "9100", "MIN_DELAY_MS": "10", "MAX_DELAY_MS": "20", "ERROR_RATE": "0.25"}))
	if err != nil {
		t.Fatal(err)
	}
	want := Config{Port: "9100", MinDelay: 10 * time.Millisecond, MaxDelay: 20 * time.Millisecond, ErrorRate: 0.25}
	if cfg != want {
		t.Fatalf("cfg = %+v, want %+v", cfg, want)
	}
}

func TestConfigRejectsInvertedDelayRange(t *testing.T) {
	if _, err := ConfigFromEnv(env(map[string]string{"MIN_DELAY_MS": "5000", "MAX_DELAY_MS": "1000"})); err == nil {
		t.Fatal("expected error")
	}
}

func TestConfigRejectsInvalidErrorRate(t *testing.T) {
	if _, err := ConfigFromEnv(env(map[string]string{"ERROR_RATE": "1.5"})); err == nil {
		t.Fatal("expected error")
	}
}

func TestConfigRejectsNonNumericDelay(t *testing.T) {
	if _, err := ConfigFromEnv(env(map[string]string{"MIN_DELAY_MS": "abc"})); err == nil {
		t.Fatal("expected error")
	}
}
