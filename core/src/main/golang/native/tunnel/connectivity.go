package tunnel

import (
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

var proxyResetTimes sync.Map

func ResetHealthCheck(name string) {
	p := tunnel.Proxies()[name]
	if p == nil {
		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return
	}

	now := time.Now()
	for _, pr := range g.Providers() {
		for _, px := range pr.Proxies() {
			proxyResetTimes.Store(px.Name(), now)
		}
	}
	for _, px := range g.Proxies() {
		proxyResetTimes.Store(px.Name(), now)
	}
}

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		ResetHealthCheck(g)
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}
