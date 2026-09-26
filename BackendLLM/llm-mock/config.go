package main

import (
	"fmt"
	"strconv"
	"time"
)

type Config struct {
	Port      string
	MinDelay  time.Duration
	MaxDelay  time.Duration
	ErrorRate float64
}

func ConfigFromEnv(getenv func(string) string) (Config, error) {
	cfg := Config{Port: "9000", MinDelay: 2000 * time.Millisecond, MaxDelay: 5000 * time.Millisecond}

	if v := getenv("PORT"); v != "" {
		cfg.Port = v
	}
	var err error
	if cfg.MinDelay, err = durationMs(getenv, "MIN_DELAY_MS", cfg.MinDelay); err != nil {
		return Config{}, err
	}
	if cfg.MaxDelay, err = durationMs(getenv, "MAX_DELAY_MS", cfg.MaxDelay); err != nil {
		return Config{}, err
	}
	if v := getenv("ERROR_RATE"); v != "" {
		if cfg.ErrorRate, err = strconv.ParseFloat(v, 64); err != nil {
			return Config{}, fmt.Errorf("ERROR_RATE: %w", err)
		}
	}

	if cfg.MinDelay < 0 || cfg.MaxDelay < cfg.MinDelay {
		return Config{}, fmt.Errorf("invalid delay range: min=%s max=%s", cfg.MinDelay, cfg.MaxDelay)
	}
	if cfg.ErrorRate < 0 || cfg.ErrorRate > 1 {
		return Config{}, fmt.Errorf("ERROR_RATE must be between 0 and 1, got %v", cfg.ErrorRate)
	}
	return cfg, nil
}

func durationMs(getenv func(string) string, key string, def time.Duration) (time.Duration, error) {
	v := getenv(key)
	if v == "" {
		return def, nil
	}
	ms, err := strconv.Atoi(v)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", key, err)
	}
	return time.Duration(ms) * time.Millisecond, nil
}
