package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)
        val urlTestJobs = mutableMapOf<Int, Job>()

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

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
                                return@onReceive
                            }
                            urlTestJobs[groupIndex] = launch {
                                try {
                                    design.clearGroupDelays(groupIndex)

                                    val testJob = launch {
                                        withClash {
                                            healthCheck(groupName)
                                        }
                                    }
                                    while (testJob.isActive) {
                                        delay(200)
                                        if (!testJob.isActive) break
                                        val group = reloadLock.withPermit {
                                            withClash {
                                                queryProxyGroup(groupName, uiStore.proxySort)
                                            }
                                        }
                                        states[groupIndex].now = group.now
                                        design.updateGroupDelays(groupIndex, group.proxies)
                                    }
                                    testJob.join()
                                } catch (e: Exception) {
                                    // ignore failure
                                } finally {
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
                                            group.proxies,
                                            group.type == "Selector",
                                            state,
                                            unorderedStates,
                                            urlTesting = false
                                        )
                                    } finally {
                                        urlTestJobs.remove(groupIndex)
                                    }
                                }
                            }
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