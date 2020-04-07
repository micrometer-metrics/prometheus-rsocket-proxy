#!/usr/bin/env bash
docker run -p 8080:8080 -p 7001:7001 \
micrometermetrics/prometheus-rsocket-proxy:latest
