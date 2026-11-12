package tunnel

import (
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// healthCheckCacheWindow mirrors the result cache window that mihomo uses for
// provider health checks (singledo.NewSingle(time.Second) in adapter/provider).
const healthCheckCacheWindow = time.Second

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

	started := time.Now()

	runProviderHealthChecks(g.Providers())

	pending := untestedProviders(g, started)
	if len(pending) == 0 {
		return
	}

	// mihomo serves a provider health check from its result cache (and coalesces
	// checks that are still running) for one second. A request that arrives right
	// after the previous test finished therefore runs no test at all, which the UI
	// reports as a test that ends immediately. When the results we just looked at
	// are that fresh, wait the window out and run those providers again.
	newest, found := lastTestTime(g)
	if !found || time.Since(newest) >= healthCheckCacheWindow {
		return
	}

	wait := newest.Add(healthCheckCacheWindow + 100*time.Millisecond).Sub(time.Now())
	if wait < 0 {
		wait = 0
	}
	if wait > 2*time.Second {
		wait = 2 * time.Second
	}

	log.Debugln("Health check for `%s` was served from the result cache, retrying in %s", name, wait)

	time.Sleep(wait)

	runProviderHealthChecks(pending)
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		ResetHealthCheck(g)
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}

func runProviderHealthChecks(providers []provider.ProxyProvider) {
	wg := &sync.WaitGroup{}

	for _, pr := range providers {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

// untestedProviders returns the providers that produced no fresh result even though
// they do have proxies to test.
func untestedProviders(g outboundgroup.ProxyGroup, since time.Time) []provider.ProxyProvider {
	pending := make([]provider.ProxyProvider, 0, 4)

	for _, pr := range g.Providers() {
		if pr.HealthCheckURL() == "" {
			continue
		}

		proxies := pr.Proxies()
		if len(proxies) == 0 {
			continue
		}

		if testedSince(proxies, since) {
			continue
		}

		pending = append(pending, pr)
	}

	return pending
}

func lastTestTime(g outboundgroup.ProxyGroup) (time.Time, bool) {
	proxies := make([]C.Proxy, 0, 32)

	for _, pr := range g.Providers() {
		proxies = append(proxies, pr.Proxies()...)
	}
	proxies = append(proxies, g.Proxies()...)

	var newest time.Time
	found := false

	for _, p := range proxies {
		for _, state := range p.ExtraDelayHistories() {
			if len(state.History) == 0 {
				continue
			}

			t := state.History[len(state.History)-1].Time
			if !found || t.After(newest) {
				newest = t
				found = true
			}
		}
	}

	return newest, found
}

func testedSince(proxies []C.Proxy, since time.Time) bool {
	for _, p := range proxies {
		for _, state := range p.ExtraDelayHistories() {
			if len(state.History) == 0 {
				continue
			}

			if !state.History[len(state.History)-1].Time.Before(since) {
				return true
			}
		}
	}

	return false
}
