package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val UNKNOWN_DELAY = 0xffff
private const val MAX_PROXY_PING_TIMES = 20

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)
        val urlTestJobs = mutableMapOf<Int, Job>()
        val urlTestPending = mutableSetOf<Int>()

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        suspend fun runUrlTest(groupIndex: Int, groupName: String) {
            var cancelled = false
            val maxDelays = HashMap<String, Int>()

            try {
                do {
                    urlTestPending.remove(groupIndex)
                    maxDelays.clear()

                    design.clearGroupDelays(groupIndex)

                    val rounds = uiStore.proxyPingTimes.coerceIn(1, MAX_PROXY_PING_TIMES)

                    val testJob = launch {
                        repeat(rounds) {
                            withClash {
                                healthCheck(groupName)
                            }

                            if (rounds > 1) {
                                // keep the worst delay of every proxy across the rounds
                                val proxies = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(groupName, uiStore.proxySort)
                                    }
                                }.proxies

                                proxies.forEach { proxy ->
                                    if (proxy.delay in 1 until UNKNOWN_DELAY) {
                                        val previous = maxDelays[proxy.name] ?: 0

                                        if (proxy.delay > previous) {
                                            maxDelays[proxy.name] = proxy.delay
                                        }
                                    }
                                }
                            }
                        }
                    }

                    try {
                        while (testJob.isActive) {
                            delay(200)

                            if (!testJob.isActive) break

                            val group = reloadLock.withPermit {
                                withClash {
                                    queryProxyGroup(groupName, uiStore.proxySort)
                                }
                            }

                            states[groupIndex].now = group.now

                            design.updateGroupDelays(groupIndex, group.proxies, maxOnly = rounds > 1)
                        }

                        testJob.join()
                    } finally {
                        testJob.cancel()
                    }
                } while (urlTestPending.remove(groupIndex))
            } catch (e: CancellationException) {
                cancelled = true

                throw e
            } catch (e: Exception) {
                // ignore failure
            } finally {
                // a click that landed while this test was finishing still has to run
                val rerun = !cancelled && urlTestPending.remove(groupIndex)

                if (cancelled) {
                    design.setUrlTesting(groupIndex, false)
                } else {
                    withContext(NonCancellable) {
                        try {
                            val group = reloadLock.withPermit {
                                withClash {
                                    queryProxyGroup(groupName, uiStore.proxySort)
                                }
                            }
                            val state = states[groupIndex]

                            state.now = group.now

                            design.updateGroup(
                                groupIndex,
                                if (maxDelays.isEmpty()) {
                                    group.proxies
                                } else {
                                    group.proxies.map { proxy ->
                                        proxy.copy(delay = maxDelays[proxy.name] ?: proxy.delay)
                                    }
                                },
                                group.type == "Selector",
                                state,
                                unorderedStates,
                                urlTesting = rerun
                            )
                        } catch (e: Exception) {
                            design.setUrlTesting(groupIndex, rerun)
                        }
                    }
                }

                urlTestJobs.remove(groupIndex)

                if (rerun) {
                    val job = launch(start = CoroutineStart.LAZY) { runUrlTest(groupIndex, groupName) }

                    urlTestJobs[groupIndex] = job

                    job.start()
                }
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                val isTesting = urlTestJobs[it.index]?.isActive == true

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates,
                                    urlTesting = isTesting
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            val groupIndex = it.index
                            val groupName = names.getOrNull(groupIndex) ?: return@onReceive
                            if (urlTestJobs[groupIndex]?.isActive == true) {
                                // never drop the click: run once more when this test ends
                                urlTestPending.add(groupIndex)

                                return@onReceive
                            }

                            val job = launch(start = CoroutineStart.LAZY) { runUrlTest(groupIndex, groupName) }

                            urlTestJobs[groupIndex] = job

                            job.start()
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }
}