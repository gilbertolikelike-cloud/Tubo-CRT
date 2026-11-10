// VK_LAYER_dsr_dynrender
//
// Camada Vulkan que "finge" ter VK_KHR_dynamic_rendering em GPUs que nao tem
// (ex.: Adreno 512 / Vulkan 1.1.87) e traduz:
//
//   vkCmdBeginRendering / vkCmdEndRendering
//     -> vkCmdBeginRenderPass / vkCmdEndRenderPass (com VkRenderPass + VkFramebuffer
//        criados sob demanda e guardados em cache)
//   vkCreateGraphicsPipelines (com VkPipelineRenderingCreateInfo)
//     -> o mesmo, mas com um VkRenderPass compativel preenchido
//
// NAO foi compilada nem testada em um aparelho real por quem escreveu. Veja o README.

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#define LAYER_NAME "VK_LAYER_dsr_dynrender"
#define LOG_TAG "DSRLayer"
#define EXPORT extern "C" __attribute__((visibility("default")))

// ---------------------------------------------------------------------------
// Log (logcat + arquivo em Download/DSRNative/logs/layer_log.txt)
// ---------------------------------------------------------------------------
static std::mutex g_logMtx;
static FILE* g_logFile = nullptr;
static bool g_logTried = false;
static int g_logLines = 0;

static void dlog(const char* fmt, ...) {
    char buf[640];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);

    std::lock_guard<std::mutex> l(g_logMtx);
    if (!g_logTried) {
        g_logTried = true;
        g_logFile = fopen("/storage/emulated/0/Download/DSRNative/logs/layer_log.txt", "a");
    }
    if (g_logFile && g_logLines < 3000) {
        ++g_logLines;
        fputs(buf, g_logFile);
        fputc('\n', g_logFile);
        fflush(g_logFile);
    }
}

// ---------------------------------------------------------------------------
// Estruturas de dados
// ---------------------------------------------------------------------------
struct InstanceData {
    VkInstance inst = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties EnumDevExt = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 GetFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceProperties GetProps = nullptr;
};

struct ViewInfo {
    VkFormat fmt = VK_FORMAT_UNDEFINED;
    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
};

struct FbEntry {
    VkFramebuffer fb = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
};

struct DeviceData {
    VkDevice dev = nullptr;
    bool emulate = false;
    PFN_vkGetDeviceProcAddr gdpa = nullptr;

    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkCreateRenderPass CreateRenderPass = nullptr;
    PFN_vkDestroyRenderPass DestroyRenderPass = nullptr;
    PFN_vkCreateFramebuffer CreateFramebuffer = nullptr;
    PFN_vkDestroyFramebuffer DestroyFramebuffer = nullptr;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass CmdEndRenderPass = nullptr;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines = nullptr;
    PFN_vkCreateImage CreateImage = nullptr;
    PFN_vkDestroyImage DestroyImage = nullptr;
    PFN_vkCreateImageView CreateImageView = nullptr;
    PFN_vkDestroyImageView DestroyImageView = nullptr;
    PFN_vkCmdBeginRendering NextBegin = nullptr;  // so existe se o driver tiver de verdade
    PFN_vkCmdEndRendering NextEnd = nullptr;

    std::mutex mtx;
    std::unordered_map<std::string, VkRenderPass> renderPasses;
    std::unordered_map<std::string, FbEntry> framebuffers;
    std::unordered_map<VkImageView, ViewInfo> views;
    std::unordered_map<VkImage, VkSampleCountFlagBits> imageSamples;
    std::unordered_set<VkCommandBuffer> open;  // command buffers com render pass aberto por nos
};

static std::mutex g_mtx;
static std::unordered_map<void*, InstanceData*> g_inst;
static std::unordered_map<void*, DeviceData*> g_dev;

static inline void* dkey(const void* h) { return *(void* const*)h; }

template <class T>
static T* lookup(std::unordered_map<void*, T*>& m, const void* h) {
    if (!h) return nullptr;
    std::lock_guard<std::mutex> l(g_mtx);
    auto it = m.find(dkey(h));
    if (it != m.end()) return it->second;
    if (m.size() == 1) return m.begin()->second;  // plano B: so ha um objeto
    return nullptr;
}

// ---------------------------------------------------------------------------
// Descricao de um render pass (so o que importa para compatibilidade/uso)
// ---------------------------------------------------------------------------
struct Att {
    VkFormat fmt = VK_FORMAT_UNDEFINED;  // UNDEFINED = anexo nao usado
    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
    VkAttachmentLoadOp load = VK_ATTACHMENT_LOAD_OP_LOAD;
    VkAttachmentStoreOp store = VK_ATTACHMENT_STORE_OP_STORE;
    VkAttachmentLoadOp sload = VK_ATTACHMENT_LOAD_OP_LOAD;
    VkAttachmentStoreOp sstore = VK_ATTACHMENT_STORE_OP_STORE;
    VkImageLayout layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
};

struct Spec {
    uint32_t colorCount = 0;
    Att color[8];
    bool hasDS = false;
    Att ds;
};

template <class T>
static void put(std::string& s, const T& v) {
    s.append(reinterpret_cast<const char*>(&v), sizeof(v));
}

static void putAtt(std::string& k, const Att& a) {
    put(k, (uint32_t)a.fmt);
    put(k, (uint32_t)a.samples);
    put(k, (uint32_t)a.load);
    put(k, (uint32_t)a.store);
    put(k, (uint32_t)a.sload);
    put(k, (uint32_t)a.sstore);
    put(k, (uint32_t)a.layout);
}

static std::string specKey(const Spec& s) {
    std::string k;
    put(k, s.colorCount);
    for (uint32_t i = 0; i < s.colorCount; ++i) putAtt(k, s.color[i]);
    put(k, (uint32_t)s.hasDS);
    if (s.hasDS) putAtt(k, s.ds);
    return k;
}

static VkAttachmentLoadOp mapLoad(VkAttachmentLoadOp o) {
    return (uint32_t)o <= 2u ? o : VK_ATTACHMENT_LOAD_OP_LOAD;  // NONE -> LOAD
}
static VkAttachmentStoreOp mapStore(VkAttachmentStoreOp o) {
    return (uint32_t)o <= 1u ? o : VK_ATTACHMENT_STORE_OP_STORE;  // NONE -> STORE
}

// Layouts "novos" (Vulkan 1.2/1.3) nao existem num driver 1.1 -> traduz para os antigos.
static VkImageLayout mapLayout(VkImageLayout l, bool ds) {
    switch (l) {
        case VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL:
        case VK_IMAGE_LAYOUT_STENCIL_ATTACHMENT_OPTIMAL:
        case VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_STENCIL_ATTACHMENT_OPTIMAL:
        case VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_STENCIL_READ_ONLY_OPTIMAL:
            return VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        case VK_IMAGE_LAYOUT_DEPTH_READ_ONLY_OPTIMAL:
        case VK_IMAGE_LAYOUT_STENCIL_READ_ONLY_OPTIMAL:
            return VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
        case VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL:
            return ds ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                      : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        case VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL:
            return ds ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
                      : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        case VK_IMAGE_LAYOUT_UNDEFINED:
            return ds ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                      : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        default:
            return l;
    }
}

// ---------------------------------------------------------------------------
// Render pass / framebuffer sob demanda
// ---------------------------------------------------------------------------
static VkRenderPass getRenderPass(DeviceData* d, const Spec& s) {
    const std::string key = specKey(s);
    {
        std::lock_guard<std::mutex> l(d->mtx);
        auto it = d->renderPasses.find(key);
        if (it != d->renderPasses.end()) return it->second;
    }

    std::vector<VkAttachmentDescription> ad;
    VkAttachmentReference cref[8];
    VkAttachmentReference dref{};
    for (uint32_t i = 0; i < s.colorCount; ++i) {
        const Att& a = s.color[i];
        if (a.fmt == VK_FORMAT_UNDEFINED) {
            cref[i].attachment = VK_ATTACHMENT_UNUSED;
            cref[i].layout = VK_IMAGE_LAYOUT_UNDEFINED;
            continue;
        }
        VkAttachmentDescription x{};
        x.format = a.fmt;
        x.samples = a.samples;
        x.loadOp = a.load;
        x.storeOp = a.store;
        x.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        x.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        x.initialLayout = a.layout;
        x.finalLayout = a.layout;
        cref[i].attachment = (uint32_t)ad.size();
        cref[i].layout = a.layout;
        ad.push_back(x);
    }
    if (s.hasDS) {
        const Att& a = s.ds;
        VkAttachmentDescription x{};
        x.format = a.fmt;
        x.samples = a.samples;
        x.loadOp = a.load;
        x.storeOp = a.store;
        x.stencilLoadOp = a.sload;
        x.stencilStoreOp = a.sstore;
        x.initialLayout = a.layout;
        x.finalLayout = a.layout;
        dref.attachment = (uint32_t)ad.size();
        dref.layout = a.layout;
        ad.push_back(x);
    }

    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = s.colorCount;
    sub.pColorAttachments = s.colorCount ? cref : nullptr;
    sub.pDepthStencilAttachment = s.hasDS ? &dref : nullptr;

    VkRenderPassCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount = (uint32_t)ad.size();
    ci.pAttachments = ad.empty() ? nullptr : ad.data();
    ci.subpassCount = 1;
    ci.pSubpasses = &sub;

    VkRenderPass rp = VK_NULL_HANDLE;
    VkResult r = d->CreateRenderPass(d->dev, &ci, nullptr, &rp);
    if (r != VK_SUCCESS) {
        dlog("ERRO: vkCreateRenderPass falhou (%d)", (int)r);
        return VK_NULL_HANDLE;
    }
    std::lock_guard<std::mutex> l(d->mtx);
    auto ins = d->renderPasses.emplace(key, rp);
    if (!ins.second) {  // outra thread criou antes
        d->DestroyRenderPass(d->dev, rp, nullptr);
        return ins.first->second;
    }
    dlog("render pass #%zu criado (%u cores%s)", d->renderPasses.size(), s.colorCount,
         s.hasDS ? " + depth/stencil" : "");
    return rp;
}

static VkFramebuffer getFramebuffer(DeviceData* d, VkRenderPass rp, const VkImageView* views, uint32_t n,
                                    uint32_t w, uint32_t h, uint32_t layers) {
    std::string key;
    put(key, rp);
    put(key, n);
    for (uint32_t i = 0; i < n; ++i) put(key, views[i]);
    put(key, w);
    put(key, h);
    put(key, layers);
    {
        std::lock_guard<std::mutex> l(d->mtx);
        auto it = d->framebuffers.find(key);
        if (it != d->framebuffers.end()) return it->second.fb;
    }
    VkFramebufferCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    ci.renderPass = rp;
    ci.attachmentCount = n;
    ci.pAttachments = n ? views : nullptr;
    ci.width = w;
    ci.height = h;
    ci.layers = layers;
    VkFramebuffer fb = VK_NULL_HANDLE;
    VkResult r = d->CreateFramebuffer(d->dev, &ci, nullptr, &fb);
    if (r != VK_SUCCESS) {
        dlog("ERRO: vkCreateFramebuffer falhou (%d) %ux%u", (int)r, w, h);
        return VK_NULL_HANDLE;
    }
    std::lock_guard<std::mutex> l(d->mtx);
    FbEntry e;
    e.fb = fb;
    e.views.assign(views, views + n);
    d->framebuffers[key] = std::move(e);
    return fb;
}

static ViewInfo getView(DeviceData* d, VkImageView v) {
    std::lock_guard<std::mutex> l(d->mtx);
    auto it = d->views.find(v);
    if (it != d->views.end()) return it->second;
    return ViewInfo{};
}

// ---------------------------------------------------------------------------
// Hooks de dispositivo
// ---------------------------------------------------------------------------
static std::atomic<uint64_t> g_begins{0};
static std::atomic<uint64_t> g_patchedPipelines{0};
static std::atomic<bool> g_warnedUnknownView{false};
static std::atomic<bool> g_warnedSecondary{false};

static void VKAPI_CALL L_CmdBeginRendering(VkCommandBuffer cb, const VkRenderingInfo* ri) {
    DeviceData* d = lookup(g_dev, cb);
    if (!d) return;
    if (!d->emulate) {
        if (d->NextBegin) d->NextBegin(cb, ri);
        return;
    }

    Spec s;
    VkImageView views[9];
    VkClearValue clears[9];
    uint32_t n = 0;

    s.colorCount = std::min<uint32_t>(ri->colorAttachmentCount, 8u);
    for (uint32_t i = 0; i < s.colorCount; ++i) {
        const VkRenderingAttachmentInfo& a = ri->pColorAttachments[i];
        if (!a.imageView) continue;  // slot vazio
        ViewInfo vi = getView(d, a.imageView);
        if (vi.fmt == VK_FORMAT_UNDEFINED && !g_warnedUnknownView.exchange(true))
            dlog("AVISO: image view desconhecida em vkCmdBeginRendering (formato indefinido)");
        Att& x = s.color[i];
        x.fmt = vi.fmt;
        x.samples = vi.samples;
        x.load = mapLoad(a.loadOp);
        x.store = mapStore(a.storeOp);
        x.layout = mapLayout(a.imageLayout, false);
        views[n] = a.imageView;
        clears[n] = a.clearValue;
        ++n;
    }

    const VkRenderingAttachmentInfo* da =
        (ri->pDepthAttachment && ri->pDepthAttachment->imageView) ? ri->pDepthAttachment : nullptr;
    const VkRenderingAttachmentInfo* sa =
        (ri->pStencilAttachment && ri->pStencilAttachment->imageView) ? ri->pStencilAttachment : nullptr;
    if (da || sa) {
        const VkRenderingAttachmentInfo* base = da ? da : sa;
        ViewInfo vi = getView(d, base->imageView);
        s.hasDS = true;
        Att& x = s.ds;
        x.fmt = vi.fmt;
        x.samples = vi.samples;
        if (da) {
            x.load = mapLoad(da->loadOp);
            x.store = mapStore(da->storeOp);
        }
        if (sa) {
            x.sload = mapLoad(sa->loadOp);
            x.sstore = mapStore(sa->storeOp);
        }
        x.layout = mapLayout(base->imageLayout, true);
        views[n] = base->imageView;
        std::memset(&clears[n], 0, sizeof(VkClearValue));
        if (da) clears[n].depthStencil.depth = da->clearValue.depthStencil.depth;
        if (sa) clears[n].depthStencil.stencil = sa->clearValue.depthStencil.stencil;
        ++n;
    }

    if ((ri->flags & VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT) && !g_warnedSecondary.exchange(true))
        dlog("AVISO: o jogo usa command buffers secundarios dentro de rendering (nao suportado)");

    VkRenderPass rp = getRenderPass(d, s);
    uint32_t w = (uint32_t)ri->renderArea.offset.x + ri->renderArea.extent.width;
    uint32_t h = (uint32_t)ri->renderArea.offset.y + ri->renderArea.extent.height;
    uint32_t layers = std::max<uint32_t>(ri->layerCount, 1u);
    VkFramebuffer fb = rp ? getFramebuffer(d, rp, views, n, w, h, layers) : VK_NULL_HANDLE;

    uint64_t c = ++g_begins;
    if (c <= 3 || c == 100 || c == 1000 || c % 20000 == 0)
        dlog("vkCmdBeginRendering #%llu: %u anexos, area %ux%u, rp=%p fb=%p", (unsigned long long)c, n, w, h,
             (void*)rp, (void*)fb);

    if (!rp || !fb) return;

    VkRenderPassBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    bi.renderPass = rp;
    bi.framebuffer = fb;
    bi.renderArea = ri->renderArea;
    bi.clearValueCount = n;
    bi.pClearValues = clears;
    d->CmdBeginRenderPass(cb, &bi, (ri->flags & VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT)
                                       ? VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS
                                       : VK_SUBPASS_CONTENTS_INLINE);
    std::lock_guard<std::mutex> l(d->mtx);
    d->open.insert(cb);
}

static void VKAPI_CALL L_CmdEndRendering(VkCommandBuffer cb) {
    DeviceData* d = lookup(g_dev, cb);
    if (!d) return;
    if (!d->emulate) {
        if (d->NextEnd) d->NextEnd(cb);
        return;
    }
    bool wasOpen;
    {
        std::lock_guard<std::mutex> l(d->mtx);
        wasOpen = d->open.erase(cb) > 0;
    }
    if (wasOpen) d->CmdEndRenderPass(cb);
}

static VkResult VKAPI_CALL L_CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                                     const VkGraphicsPipelineCreateInfo* infos,
                                                     const VkAllocationCallbacks* alloc, VkPipeline* out) {
    DeviceData* d = lookup(g_dev, device);
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    if (!d->emulate) return d->CreateGraphicsPipelines(device, cache, count, infos, alloc, out);

    std::vector<VkGraphicsPipelineCreateInfo> copy(infos, infos + count);
    for (uint32_t i = 0; i < count; ++i) {
        VkGraphicsPipelineCreateInfo& ci = copy[i];
        if (ci.renderPass != VK_NULL_HANDLE) continue;

        const VkPipelineRenderingCreateInfo* ri = nullptr;
        for (auto p = (const VkBaseInStructure*)ci.pNext; p; p = p->pNext) {
            if (p->sType == VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO) {
                ri = (const VkPipelineRenderingCreateInfo*)p;
                break;
            }
        }
        if (!ri) continue;

        VkSampleCountFlagBits sc =
            ci.pMultisampleState ? ci.pMultisampleState->rasterizationSamples : VK_SAMPLE_COUNT_1_BIT;
        Spec s;
        s.colorCount = std::min<uint32_t>(ri->colorAttachmentCount, 8u);
        for (uint32_t k = 0; k < s.colorCount; ++k) {
            s.color[k].fmt = ri->pColorAttachmentFormats[k];
            s.color[k].samples = sc;
        }
        VkFormat dsf = ri->depthAttachmentFormat != VK_FORMAT_UNDEFINED ? ri->depthAttachmentFormat
                                                                          : ri->stencilAttachmentFormat;
        if (dsf != VK_FORMAT_UNDEFINED) {
            s.hasDS = true;
            s.ds.fmt = dsf;
            s.ds.samples = sc;
            s.ds.layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        }
        VkRenderPass rp = getRenderPass(d, s);
        if (rp) {
            ci.renderPass = rp;
            ci.subpass = 0;
            ++g_patchedPipelines;
        }
    }
    uint64_t pp = g_patchedPipelines.load();
    if (pp && (pp <= 3 || pp % 200 == 0)) dlog("pipelines ajustados para render pass: %llu", (unsigned long long)pp);
    return d->CreateGraphicsPipelines(device, cache, count, copy.data(), alloc, out);
}

static VkResult VKAPI_CALL L_CreateImage(VkDevice device, const VkImageCreateInfo* ci,
                                         const VkAllocationCallbacks* a, VkImage* out) {
    DeviceData* d = lookup(g_dev, device);
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = d->CreateImage(device, ci, a, out);
    if (r == VK_SUCCESS && d->emulate) {
        std::lock_guard<std::mutex> l(d->mtx);
        d->imageSamples[*out] = ci->samples;
    }
    return r;
}

static void VKAPI_CALL L_DestroyImage(VkDevice device, VkImage img, const VkAllocationCallbacks* a) {
    DeviceData* d = lookup(g_dev, device);
    if (!d) return;
    if (d->emulate) {
        std::lock_guard<std::mutex> l(d->mtx);
        d->imageSamples.erase(img);
    }
    d->DestroyImage(device, img, a);
}

static VkResult VKAPI_CALL L_CreateImageView(VkDevice device, const VkImageViewCreateInfo* ci,
                                             const VkAllocationCallbacks* a, VkImageView* out) {
    DeviceData* d = lookup(g_dev, device);
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = d->CreateImageView(device, ci, a, out);
    if (r == VK_SUCCESS && d->emulate) {
        std::lock_guard<std::mutex> l(d->mtx);
        ViewInfo vi;
        vi.fmt = ci->format;
        auto it = d->imageSamples.find(ci->image);
        if (it != d->imageSamples.end()) vi.samples = it->second;
        d->views[*out] = vi;
    }
    return r;
}

static void VKAPI_CALL L_DestroyImageView(VkDevice device, VkImageView view, const VkAllocationCallbacks* a) {
    DeviceData* d = lookup(g_dev, device);
    if (!d) return;
    if (d->emulate) {
        std::vector<VkFramebuffer> dead;
        {
            std::lock_guard<std::mutex> l(d->mtx);
            d->views.erase(view);
            for (auto it = d->framebuffers.begin(); it != d->framebuffers.end();) {
                const auto& v = it->second.views;
                if (std::find(v.begin(), v.end(), view) != v.end()) {
                    dead.push_back(it->second.fb);
                    it = d->framebuffers.erase(it);
                } else {
                    ++it;
                }
            }
        }
        for (VkFramebuffer fb : dead) d->DestroyFramebuffer(device, fb, nullptr);
    }
    d->DestroyImageView(device, view, a);
}

static void VKAPI_CALL L_DestroyDevice(VkDevice device, const VkAllocationCallbacks* a) {
    void* key = dkey(device);
    DeviceData* d = nullptr;
    {
        std::lock_guard<std::mutex> l(g_mtx);
        auto it = g_dev.find(key);
        if (it != g_dev.end()) {
            d = it->second;
            g_dev.erase(it);
        }
    }
    if (!d) return;
    for (auto& e : d->framebuffers) d->DestroyFramebuffer(device, e.second.fb, nullptr);
    for (auto& e : d->renderPasses) d->DestroyRenderPass(device, e.second, nullptr);
    auto next = d->DestroyDevice;
    dlog("dispositivo destruido (%llu vkCmdBeginRendering emulados)", (unsigned long long)g_begins.load());
    next(device, a);
    delete d;
}

static PFN_vkVoidFunction VKAPI_CALL L_GetDeviceProcAddr(VkDevice device, const char* name);

// ---------------------------------------------------------------------------
// Hooks de instancia
// ---------------------------------------------------------------------------
static bool nativeHasDynRendering(InstanceData* I, VkPhysicalDevice pd) {
    if (I->GetProps) {
        VkPhysicalDeviceProperties p{};
        I->GetProps(pd, &p);
        if (p.apiVersion >= VK_API_VERSION_1_3) return true;
    }
    if (!I->EnumDevExt) return false;
    uint32_t n = 0;
    I->EnumDevExt(pd, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> v(n);
    I->EnumDevExt(pd, nullptr, &n, v.data());
    for (uint32_t i = 0; i < n; ++i)
        if (!strcmp(v[i].extensionName, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) return true;
    return false;
}

static VkResult VKAPI_CALL L_CreateInstance(const VkInstanceCreateInfo* ci, const VkAllocationCallbacks* a,
                                            VkInstance* out) {
    auto* lci = const_cast<VkLayerInstanceCreateInfo*>((const VkLayerInstanceCreateInfo*)ci->pNext);
    while (lci && !(lci->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && lci->function == VK_LAYER_LINK_INFO))
        lci = const_cast<VkLayerInstanceCreateInfo*>((const VkLayerInstanceCreateInfo*)lci->pNext);
    if (!lci || !lci->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr gipa = lci->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    lci->u.pLayerInfo = lci->u.pLayerInfo->pNext;

    auto create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = create(ci, a, out);
    if (r != VK_SUCCESS) return r;

    auto* I = new InstanceData;
    I->inst = *out;
    I->gipa = gipa;
    I->DestroyInstance = (PFN_vkDestroyInstance)gipa(*out, "vkDestroyInstance");
    I->EnumDevExt = (PFN_vkEnumerateDeviceExtensionProperties)gipa(*out, "vkEnumerateDeviceExtensionProperties");
    I->GetFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)gipa(*out, "vkGetPhysicalDeviceFeatures2");
    if (!I->GetFeatures2)
        I->GetFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)gipa(*out, "vkGetPhysicalDeviceFeatures2KHR");
    I->GetProps = (PFN_vkGetPhysicalDeviceProperties)gipa(*out, "vkGetPhysicalDeviceProperties");
    {
        std::lock_guard<std::mutex> l(g_mtx);
        g_inst[dkey(*out)] = I;
    }
    dlog("camada " LAYER_NAME " carregada (instancia criada)");
    return VK_SUCCESS;
}

static void VKAPI_CALL L_DestroyInstance(VkInstance inst, const VkAllocationCallbacks* a) {
    void* key = dkey(inst);
    InstanceData* I = nullptr;
    {
        std::lock_guard<std::mutex> l(g_mtx);
        auto it = g_inst.find(key);
        if (it != g_inst.end()) {
            I = it->second;
            g_inst.erase(it);
        }
    }
    if (!I) return;
    auto next = I->DestroyInstance;
    next(inst, a);
    delete I;
}

static VkResult VKAPI_CALL L_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo* ci,
                                          const VkAllocationCallbacks* a, VkDevice* out) {
    InstanceData* I = lookup(g_inst, pd);
    if (!I) return VK_ERROR_INITIALIZATION_FAILED;

    auto* lci = const_cast<VkLayerDeviceCreateInfo*>((const VkLayerDeviceCreateInfo*)ci->pNext);
    while (lci && !(lci->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && lci->function == VK_LAYER_LINK_INFO))
        lci = const_cast<VkLayerDeviceCreateInfo*>((const VkLayerDeviceCreateInfo*)lci->pNext);
    if (!lci || !lci->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr gipa = lci->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr gdpa = lci->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    lci->u.pLayerInfo = lci->u.pLayerInfo->pNext;

    auto next = (PFN_vkCreateDevice)gipa(I->inst, "vkCreateDevice");
    if (!next) return VK_ERROR_INITIALIZATION_FAILED;

    const bool emulate = !nativeHasDynRendering(I, pd);

    VkDeviceCreateInfo nci = *ci;
    std::vector<const char*> exts;
    VkBaseOutStructure* unlinkedPrev = nullptr;
    VkBaseOutStructure* unlinked = nullptr;
    if (emulate) {
        // o driver nao conhece a extensao: tira da lista...
        for (uint32_t i = 0; i < ci->enabledExtensionCount; ++i)
            if (strcmp(ci->ppEnabledExtensionNames[i], VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME) != 0)
                exts.push_back(ci->ppEnabledExtensionNames[i]);
        nci.enabledExtensionCount = (uint32_t)exts.size();
        nci.ppEnabledExtensionNames = exts.empty() ? nullptr : exts.data();
        // ...e tira a struct de features do pNext (restaurada depois da chamada)
        VkBaseOutStructure* prev = nullptr;
        VkBaseOutStructure* cur = (VkBaseOutStructure*)nci.pNext;
        while (cur) {
            if (cur->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES) {
                if (prev)
                    prev->pNext = cur->pNext;
                else
                    nci.pNext = cur->pNext;
                unlinkedPrev = prev;
                unlinked = cur;
                break;
            }
            prev = cur;
            cur = cur->pNext;
        }
    }

    VkResult r = next(pd, &nci, a, out);
    if (unlinked && unlinkedPrev) unlinkedPrev->pNext = unlinked;
    if (r != VK_SUCCESS) return r;

    auto* d = new DeviceData;
    d->dev = *out;
    d->emulate = emulate;
    d->gdpa = gdpa;
#define LOADD(field, name) d->field = (decltype(d->field))gdpa(*out, name)
    LOADD(DestroyDevice, "vkDestroyDevice");
    LOADD(CreateRenderPass, "vkCreateRenderPass");
    LOADD(DestroyRenderPass, "vkDestroyRenderPass");
    LOADD(CreateFramebuffer, "vkCreateFramebuffer");
    LOADD(DestroyFramebuffer, "vkDestroyFramebuffer");
    LOADD(CmdBeginRenderPass, "vkCmdBeginRenderPass");
    LOADD(CmdEndRenderPass, "vkCmdEndRenderPass");
    LOADD(CreateGraphicsPipelines, "vkCreateGraphicsPipelines");
    LOADD(CreateImage, "vkCreateImage");
    LOADD(DestroyImage, "vkDestroyImage");
    LOADD(CreateImageView, "vkCreateImageView");
    LOADD(DestroyImageView, "vkDestroyImageView");
    LOADD(NextBegin, "vkCmdBeginRendering");
    LOADD(NextEnd, "vkCmdEndRendering");
#undef LOADD
    if (!d->NextBegin) d->NextBegin = (PFN_vkCmdBeginRendering)gdpa(*out, "vkCmdBeginRenderingKHR");
    if (!d->NextEnd) d->NextEnd = (PFN_vkCmdEndRendering)gdpa(*out, "vkCmdEndRenderingKHR");
    {
        std::lock_guard<std::mutex> l(g_mtx);
        g_dev[dkey(*out)] = d;
    }
    dlog("dispositivo criado: dynamic rendering %s", emulate ? "EMULADO via render pass" : "nativo (camada inativa)");
    return VK_SUCCESS;
}

static void VKAPI_CALL L_GetPhysicalDeviceFeatures2(VkPhysicalDevice pd, VkPhysicalDeviceFeatures2* pf) {
    InstanceData* I = lookup(g_inst, pd);
    if (!I || !I->GetFeatures2) return;
    I->GetFeatures2(pd, pf);
    if (nativeHasDynRendering(I, pd)) return;
    for (auto p = (VkBaseOutStructure*)pf->pNext; p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES) {
            ((VkPhysicalDeviceDynamicRenderingFeatures*)p)->dynamicRendering = VK_TRUE;
        } else if (p->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) {
            ((VkPhysicalDeviceVulkan13Features*)p)->dynamicRendering = VK_TRUE;
        }
    }
}

static VkResult VKAPI_CALL L_EnumerateDeviceExtensionProperties(VkPhysicalDevice pd, const char* layer,
                                                                uint32_t* cnt, VkExtensionProperties* props) {
    if (layer && !strcmp(layer, LAYER_NAME)) {
        *cnt = 0;
        return VK_SUCCESS;
    }
    InstanceData* I = lookup(g_inst, pd);
    if (!I || !I->EnumDevExt) {
        *cnt = 0;
        return VK_SUCCESS;
    }
    if (layer) return I->EnumDevExt(pd, layer, cnt, props);

    uint32_t n = 0;
    I->EnumDevExt(pd, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> v(n);
    VkResult r = I->EnumDevExt(pd, nullptr, &n, v.data());
    if (r != VK_SUCCESS && r != VK_INCOMPLETE) return r;
    v.resize(n);
    if (!nativeHasDynRendering(I, pd)) {
        VkExtensionProperties e{};
        strncpy(e.extensionName, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, VK_MAX_EXTENSION_NAME_SIZE - 1);
        e.specVersion = VK_KHR_DYNAMIC_RENDERING_SPEC_VERSION;
        v.push_back(e);
    }
    if (!props) {
        *cnt = (uint32_t)v.size();
        return VK_SUCCESS;
    }
    uint32_t m = std::min<uint32_t>(*cnt, (uint32_t)v.size());
    for (uint32_t i = 0; i < m; ++i) props[i] = v[i];
    *cnt = m;
    return m < v.size() ? VK_INCOMPLETE : VK_SUCCESS;
}

// ---------------------------------------------------------------------------
// Enumeracao da propria camada
// ---------------------------------------------------------------------------
static VkResult VKAPI_CALL L_EnumerateInstanceLayerProperties(uint32_t* cnt, VkLayerProperties* props) {
    if (!props) {
        *cnt = 1;
        return VK_SUCCESS;
    }
    if (*cnt < 1) return VK_INCOMPLETE;
    VkLayerProperties p{};
    strncpy(p.layerName, LAYER_NAME, VK_MAX_EXTENSION_NAME_SIZE - 1);
    strncpy(p.description, "DSR: emula VK_KHR_dynamic_rendering com render passes", VK_MAX_DESCRIPTION_SIZE - 1);
    p.specVersion = VK_API_VERSION_1_1;
    p.implementationVersion = 1;
    props[0] = p;
    *cnt = 1;
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL L_EnumerateInstanceExtensionProperties(const char* layer, uint32_t* cnt,
                                                                  VkExtensionProperties*) {
    if (layer && !strcmp(layer, LAYER_NAME)) {
        *cnt = 0;
        return VK_SUCCESS;
    }
    return VK_ERROR_LAYER_NOT_PRESENT;
}

static VkResult VKAPI_CALL L_EnumerateDeviceLayerProperties(VkPhysicalDevice, uint32_t* cnt, VkLayerProperties*) {
    *cnt = 0;
    return VK_SUCCESS;
}

// ---------------------------------------------------------------------------
// Tabela de hooks + GetProcAddr
// ---------------------------------------------------------------------------
struct HookEntry {
    const char* name;
    PFN_vkVoidFunction fn;
};

static PFN_vkVoidFunction VKAPI_CALL L_GetInstanceProcAddr(VkInstance inst, const char* name);

#define H(n, f) {n, (PFN_vkVoidFunction)f}
static const HookEntry kHooks[] = {
    H("vkGetInstanceProcAddr", L_GetInstanceProcAddr),
    H("vkGetDeviceProcAddr", L_GetDeviceProcAddr),
    H("vkCreateInstance", L_CreateInstance),
    H("vkDestroyInstance", L_DestroyInstance),
    H("vkCreateDevice", L_CreateDevice),
    H("vkDestroyDevice", L_DestroyDevice),
    H("vkEnumerateInstanceLayerProperties", L_EnumerateInstanceLayerProperties),
    H("vkEnumerateInstanceExtensionProperties", L_EnumerateInstanceExtensionProperties),
    H("vkEnumerateDeviceLayerProperties", L_EnumerateDeviceLayerProperties),
    H("vkEnumerateDeviceExtensionProperties", L_EnumerateDeviceExtensionProperties),
    H("vkGetPhysicalDeviceFeatures2", L_GetPhysicalDeviceFeatures2),
    H("vkGetPhysicalDeviceFeatures2KHR", L_GetPhysicalDeviceFeatures2),
    H("vkCreateGraphicsPipelines", L_CreateGraphicsPipelines),
    H("vkCreateImage", L_CreateImage),
    H("vkDestroyImage", L_DestroyImage),
    H("vkCreateImageView", L_CreateImageView),
    H("vkDestroyImageView", L_DestroyImageView),
    H("vkCmdBeginRendering", L_CmdBeginRendering),
    H("vkCmdBeginRenderingKHR", L_CmdBeginRendering),
    H("vkCmdEndRendering", L_CmdEndRendering),
    H("vkCmdEndRenderingKHR", L_CmdEndRendering),
};
#undef H

static PFN_vkVoidFunction findHook(const char* name) {
    for (const auto& h : kHooks)
        if (!strcmp(h.name, name)) return h.fn;
    return nullptr;
}

static PFN_vkVoidFunction VKAPI_CALL L_GetDeviceProcAddr(VkDevice device, const char* name) {
    if (!name) return nullptr;
    DeviceData* d = lookup(g_dev, device);
    if (!d) return nullptr;
    // com driver nativo, as funcoes de rendering vem direto do driver
    const bool renderingName = !strncmp(name, "vkCmdBeginRendering", 19) || !strncmp(name, "vkCmdEndRendering", 17);
    if (!d->emulate && renderingName) return d->gdpa(device, name);
    if (PFN_vkVoidFunction f = findHook(name)) return f;
    return d->gdpa(device, name);
}

static PFN_vkVoidFunction VKAPI_CALL L_GetInstanceProcAddr(VkInstance inst, const char* name) {
    if (!name) return nullptr;
    if (PFN_vkVoidFunction f = findHook(name)) return f;
    InstanceData* I = inst ? lookup(g_inst, inst) : nullptr;
    if (!I) return nullptr;
    return I->gipa(inst, name);
}

// ---------------------------------------------------------------------------
// Simbolos exportados (o loader do Android procura por estes nomes)
// ---------------------------------------------------------------------------
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* p) {
    if (!p || p->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (p->loaderLayerInterfaceVersion >= 2) {
        p->loaderLayerInterfaceVersion = 2;
        p->pfnGetInstanceProcAddr = L_GetInstanceProcAddr;
        p->pfnGetDeviceProcAddr = L_GetDeviceProcAddr;
        p->pfnGetPhysicalDeviceProcAddr = nullptr;
    }
    return VK_SUCCESS;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance i, const char* n) {
    return L_GetInstanceProcAddr(i, n);
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice d, const char* n) {
    return L_GetDeviceProcAddr(d, n);
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL VK_LAYER_dsr_dynrenderGetInstanceProcAddr(VkInstance i,
                                                                                         const char* n) {
    return L_GetInstanceProcAddr(i, n);
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL VK_LAYER_dsr_dynrenderGetDeviceProcAddr(VkDevice d,
                                                                                       const char* n) {
    return L_GetDeviceProcAddr(d, n);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(uint32_t* c, VkLayerProperties* p) {
    return L_EnumerateInstanceLayerProperties(c, p);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char* l, uint32_t* c,
                                                                            VkExtensionProperties* p) {
    return L_EnumerateInstanceExtensionProperties(l, c, p);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceLayerProperties(VkPhysicalDevice d, uint32_t* c,
                                                                      VkLayerProperties* p) {
    return L_EnumerateDeviceLayerProperties(d, c, p);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(VkPhysicalDevice d, const char* l,
                                                                          uint32_t* c, VkExtensionProperties* p) {
    return L_EnumerateDeviceExtensionProperties(d, l, c, p);
}
