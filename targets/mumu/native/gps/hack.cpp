#include "hack.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <unistd.h>

#include "And64InlineHook.hpp"
#include "log.h"
#include "xdl.h"
#ifdef PCC_UNIFIED
#include <atomic>
static std::atomic_bool unified_gps_ready{false};
bool pcc_gps_ready() { return unified_gps_ready.load(); }
void pcc_gps_install_after_login();
#endif

namespace {

// The unified core gates this implementation on the exact Pikmin Bloom 157
// binary before this worker is started.  Resolve the managed members from that
// already-gated runtime instead of retaining v152 RVAs: the 153 update moved
// both functions, and a stale address must never be hooked.
constexpr char kTargetVersion[] = "157.0";
constexpr int kTargetVersionCode = 1793360009;
// ExpeditionListItem.<Start>b__34_0(Unit), the actual button callback. The
// compiler duplicated OnClick into this lambda, so hooking OnClick itself
// does not observe taps.

struct LatLng {
    double lat;
    double lng;
};

using ListItemOnClickFn = void (*)(void *self, uint8_t unit, void *method);
using GetSpawnLocationFn = LatLng (*)(void *expedition, void *method);
using DomainGetFn = void *(*)();
using DomainGetAssembliesFn = const void **(*)(void *, size_t *);
using AssemblyGetImageFn = void *(*)(const void *);
using ClassFromNameFn = void *(*)(void *, const char *, const char *);
using ClassGetMethodFromNameFn = void *(*)(void *, const char *, int);
using ClassGetFieldFromNameFn = void *(*)(void *, const char *);
using FieldGetOffsetFn = int32_t (*)(void *);
using ObjectGetClassFn = void *(*)(void *);

JavaVM *g_vm = nullptr;
ListItemOnClickFn g_original_on_click = nullptr;
int32_t g_list_item_current_task_offset = -1;
void *g_libil2cpp_base = nullptr;
DomainGetFn g_domain_get = nullptr;
DomainGetAssembliesFn g_domain_get_assemblies = nullptr;
AssemblyGetImageFn g_assembly_get_image = nullptr;
ClassFromNameFn g_class_from_name = nullptr;
ClassGetMethodFromNameFn g_class_get_method_from_name = nullptr;
ClassGetFieldFromNameFn g_class_get_field_from_name = nullptr;
FieldGetOffsetFn g_field_get_offset = nullptr;
ObjectGetClassFn g_object_get_class = nullptr;
std::mutex g_copy_mutex;
double g_last_lat = 999.0;
double g_last_lng = 999.0;
int64_t g_last_copy_ms = 0;
std::string g_game_data_dir;

std::string state_path(const char *name) {
    return g_game_data_dir.empty() ? std::string() : g_game_data_dir + "/files/" + name;
}

void write_status(const char *result, const LatLng *point = nullptr) {
    const std::string path = state_path("gps_copy_status.tsv");
    if (path.empty()) return;
    FILE *file = fopen(path.c_str(), "w");
    if (file == nullptr) return;
    const double lat = point ? point->lat : 0.0;
    const double lng = point ? point->lng : 0.0;
    fprintf(file, "%lld\t%s\t%.7f\t%.7f\n",
            static_cast<long long>(time(nullptr) * 1000), result, lat, lng);
    fclose(file);
}

bool copy_is_enabled() {
    const std::string path = state_path("gps_copy_mode.txt");
    if (path.empty()) return true;
    FILE *file = fopen(path.c_str(), "r");
    if (file == nullptr) return true;  // Preserve the original no-control-file behavior.
    char mode[16]{};
    const bool enabled = fgets(mode, sizeof(mode), file) == nullptr ||
                         strncmp(mode, "off", 3) != 0;
    fclose(file);
    return enabled;
}

void append_history(const LatLng &point) {
    const std::string path = state_path("gps_copy_history.tsv");
    if (path.empty()) return;
    FILE *file = fopen(path.c_str(), "a");
    if (file == nullptr) return;
    fprintf(file, "%lld\tcopied\t%.7f\t%.7f\n",
            static_cast<long long>(time(nullptr) * 1000), point.lat, point.lng);
    fclose(file);
}

int64_t monotonic_ms() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

bool valid_coordinate(const LatLng &point) {
    return std::isfinite(point.lat) && std::isfinite(point.lng) &&
           point.lat >= -90.0 && point.lat <= 90.0 &&
           point.lng >= -180.0 && point.lng <= 180.0 &&
           (std::fabs(point.lat) > 0.0000001 || std::fabs(point.lng) > 0.0000001);
}

bool clear_jni_exception(JNIEnv *env, const char *stage) {
    if (!env->ExceptionCheck()) return false;
    LOGE("JNI exception at %s", stage);
    env->ExceptionClear();
    return true;
}

bool copy_to_clipboard_and_toast(const char *coordinates) {
    if (g_vm == nullptr) return false;

    JNIEnv *env = nullptr;
    bool attached_here = false;
    jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return false;
        }
        attached_here = true;
    } else if (status != JNI_OK || env == nullptr) {
        LOGE("GetEnv failed: %d", status);
        return false;
    }

    bool copied = false;
    if (env->PushLocalFrame(32) != JNI_OK) {
        if (attached_here) g_vm->DetachCurrentThread();
        return false;
    }

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    jmethodID current_application = activity_thread
        ? env->GetStaticMethodID(activity_thread, "currentApplication",
                                 "()Landroid/app/Application;")
        : nullptr;
    jobject application = current_application
        ? env->CallStaticObjectMethod(activity_thread, current_application)
        : nullptr;
    if (clear_jni_exception(env, "currentApplication") || application == nullptr) {
        goto cleanup;
    }

    {
        jclass context_class = env->FindClass("android/content/Context");
        jmethodID get_system_service = context_class
            ? env->GetMethodID(context_class, "getSystemService",
                               "(Ljava/lang/String;)Ljava/lang/Object;")
            : nullptr;
        jstring clipboard_name = env->NewStringUTF("clipboard");
        jobject clipboard = get_system_service
            ? env->CallObjectMethod(application, get_system_service, clipboard_name)
            : nullptr;
        if (clear_jni_exception(env, "getSystemService") || clipboard == nullptr) {
            goto cleanup;
        }

        jclass clip_data_class = env->FindClass("android/content/ClipData");
        jmethodID new_plain_text = clip_data_class
            ? env->GetStaticMethodID(
                  clip_data_class, "newPlainText",
                  "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)"
                  "Landroid/content/ClipData;")
            : nullptr;
        jstring label = env->NewStringUTF("Pikmin GPS");
        jstring text = env->NewStringUTF(coordinates);
        jobject clip = new_plain_text
            ? env->CallStaticObjectMethod(clip_data_class, new_plain_text, label, text)
            : nullptr;
        if (clear_jni_exception(env, "ClipData.newPlainText") || clip == nullptr) {
            goto cleanup;
        }

        jclass clipboard_class = env->GetObjectClass(clipboard);
        jmethodID set_primary_clip = clipboard_class
            ? env->GetMethodID(clipboard_class, "setPrimaryClip",
                               "(Landroid/content/ClipData;)V")
            : nullptr;
        if (set_primary_clip == nullptr) goto cleanup;
        env->CallVoidMethod(clipboard, set_primary_clip, clip);
        if (clear_jni_exception(env, "ClipboardManager.setPrimaryClip")) {
            goto cleanup;
        }
        // A successful binder call can still be refused by clipboard policy.
        // Read back from the same foreground game context before reporting copied.
        jmethodID get_primary_clip=env->GetMethodID(clipboard_class,"getPrimaryClip",
            "()Landroid/content/ClipData;");
        jobject actual=get_primary_clip?env->CallObjectMethod(clipboard,get_primary_clip):nullptr;
        if(clear_jni_exception(env,"ClipboardManager.getPrimaryClip")||!actual)goto cleanup;
        jmethodID count=env->GetMethodID(clip_data_class,"getItemCount","()I");
        jmethodID item=env->GetMethodID(clip_data_class,"getItemAt","(I)Landroid/content/ClipData$Item;");
        if(clear_jni_exception(env,"ClipData read methods")||!count||!item)goto cleanup;
        const jint items=env->CallIntMethod(actual,count);
        if(clear_jni_exception(env,"ClipData.getItemCount")||items<1)goto cleanup;
        jobject first=env->CallObjectMethod(actual,item,0);
        if(clear_jni_exception(env,"ClipData.getItemAt")||!first)goto cleanup;
        jclass item_class=env->GetObjectClass(first);
        jmethodID get_text=env->GetMethodID(item_class,"getText","()Ljava/lang/CharSequence;");
        jobject value=get_text?env->CallObjectMethod(first,get_text):nullptr;
        if(clear_jni_exception(env,"ClipData.Item.getText")||!value)goto cleanup;
        jclass string_class=env->FindClass("java/lang/String");
        jmethodID equals=env->GetMethodID(string_class,"equals","(Ljava/lang/Object;)Z");
        copied=equals&&env->CallBooleanMethod(text,equals,value);
        if(clear_jni_exception(env,"clipboard readback")||!copied)goto cleanup;

        // The debounce worker is attached to the VM but does not have a
        // Looper by default. Toast creates a Handler internally, so prepare a
        // thread Looper before constructing it.
        jclass looper_class = env->FindClass("android/os/Looper");
        jmethodID my_looper = looper_class
            ? env->GetStaticMethodID(looper_class, "myLooper",
                                     "()Landroid/os/Looper;")
            : nullptr;
        jobject looper = my_looper
            ? env->CallStaticObjectMethod(looper_class, my_looper)
            : nullptr;
        if (!clear_jni_exception(env, "Looper.myLooper") &&
            looper == nullptr && looper_class != nullptr) {
            jmethodID prepare = env->GetStaticMethodID(
                looper_class, "prepare", "()V");
            if (prepare != nullptr) {
                env->CallStaticVoidMethod(looper_class, prepare);
                clear_jni_exception(env, "Looper.prepare");
            }
        }

        jclass toast_class = env->FindClass("android/widget/Toast");
        jmethodID make_text = toast_class
            ? env->GetStaticMethodID(
                  toast_class, "makeText",
                  "(Landroid/content/Context;Ljava/lang/CharSequence;I)"
                  "Landroid/widget/Toast;")
            : nullptr;
        jstring toast_text = env->NewStringUTF("GPS 已複製");
        jobject toast = make_text
            ? env->CallStaticObjectMethod(toast_class, make_text, application, toast_text, 0)
            : nullptr;
        if (!clear_jni_exception(env, "Toast.makeText") && toast != nullptr) {
            jmethodID show = env->GetMethodID(toast_class, "show", "()V");
            if (show != nullptr) {
                env->CallVoidMethod(toast, show);
                clear_jni_exception(env, "Toast.show");
            }
        }
    }

cleanup:
    env->PopLocalFrame(nullptr);
    if (attached_here) g_vm->DetachCurrentThread();
    return copied;
}

bool copy_expedition_gps(void *expedition) {
    if (expedition == nullptr || g_object_get_class == nullptr ||
        g_class_get_method_from_name == nullptr) return false;

    // The task implementation varies by expedition kind.  Resolve its getter
    // from the live object's class, rather than assuming the v152 function
    // address or a particular concrete task type.
    void *task_class = g_object_get_class(expedition);
    void *method = task_class
        ? g_class_get_method_from_name(task_class, "get_SpawnLocation", 0)
        : nullptr;
    auto get_spawn_location = method
        ? reinterpret_cast<GetSpawnLocationFn>(*reinterpret_cast<void **>(method))
        : nullptr;
    Dl_info symbol{};
    if (get_spawn_location == nullptr ||
        !dladdr(reinterpret_cast<void *>(get_spawn_location), &symbol) ||
        symbol.dli_fbase != g_libil2cpp_base) {
        LOGE("SpawnLocation getter unavailable for selected expedition");
        write_status("spawn-method-unavailable");
        return false;
    }

    LatLng point = get_spawn_location(expedition, nullptr);
    if (!valid_coordinate(point)) {
        LOGW("selected expedition returned invalid GPS");
        write_status("invalid-coordinate");
        return false;
    }

    if (!copy_is_enabled()) {
        write_status("off", &point);
        return false;
    }

    const int64_t now = monotonic_ms();
    {
        std::lock_guard<std::mutex> lock(g_copy_mutex);
        if (std::fabs(point.lat - g_last_lat) < 0.00000001 &&
            std::fabs(point.lng - g_last_lng) < 0.00000001 &&
            now - g_last_copy_ms < 1200) {
            return true;
        }
    }

    char coordinates[64];
    snprintf(coordinates, sizeof(coordinates), "%.7f,%.7f", point.lat, point.lng);
    if (copy_to_clipboard_and_toast(coordinates)) {
        { std::lock_guard<std::mutex> lock(g_copy_mutex);
          g_last_lat=point.lat;g_last_lng=point.lng;g_last_copy_ms=now; }
        LOGI("selected expedition GPS copied and clipboard readback verified");
        write_status("copied", &point);
        append_history(point);
        return true;
    } else {
        LOGE("failed to copy or verify selected expedition GPS");
        write_status("clipboard-failed", &point);
        return false;
    }
}

void hooked_list_item_on_click(void *self, uint8_t unit, void *method) {
    if (self != nullptr && g_list_item_current_task_offset >= 0) {
        auto expedition = *reinterpret_cast<void **>(
            reinterpret_cast<uintptr_t>(self) + g_list_item_current_task_offset);
        copy_expedition_gps(expedition);
    }
    if (g_original_on_click != nullptr) {
        g_original_on_click(self, unit, method);
    }
}

void *find_class(const char *namespc, const char *name) {
    if (g_domain_get == nullptr || g_domain_get_assemblies == nullptr ||
        g_assembly_get_image == nullptr || g_class_from_name == nullptr) return nullptr;
    void *domain = g_domain_get();
    size_t count{};
    const void **assemblies = domain ? g_domain_get_assemblies(domain, &count) : nullptr;
    for (size_t index = 0; assemblies != nullptr && index < count; ++index) {
        if (void *klass = g_class_from_name(g_assembly_get_image(assemblies[index]), namespc, name)) {
            return klass;
        }
    }
    return nullptr;
}

bool install_hook() {
    void *handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
    if (handle == nullptr) return false;

    xdl_info_t info{};
    if (xdl_info(handle, XDL_DI_DLINFO, &info) != 0 || info.dli_fbase == nullptr) {
        LOGE("unable to resolve libil2cpp base");
        return true;
    }

    g_libil2cpp_base = info.dli_fbase;
    g_domain_get = reinterpret_cast<DomainGetFn>(xdl_sym(handle, "il2cpp_domain_get", nullptr));
    g_domain_get_assemblies = reinterpret_cast<DomainGetAssembliesFn>(
        xdl_sym(handle, "il2cpp_domain_get_assemblies", nullptr));
    g_assembly_get_image = reinterpret_cast<AssemblyGetImageFn>(
        xdl_sym(handle, "il2cpp_assembly_get_image", nullptr));
    g_class_from_name = reinterpret_cast<ClassFromNameFn>(
        xdl_sym(handle, "il2cpp_class_from_name", nullptr));
    g_class_get_method_from_name = reinterpret_cast<ClassGetMethodFromNameFn>(
        xdl_sym(handle, "il2cpp_class_get_method_from_name", nullptr));
    g_class_get_field_from_name = reinterpret_cast<ClassGetFieldFromNameFn>(
        xdl_sym(handle, "il2cpp_class_get_field_from_name", nullptr));
    g_field_get_offset = reinterpret_cast<FieldGetOffsetFn>(
        xdl_sym(handle, "il2cpp_field_get_offset", nullptr));
    g_object_get_class = reinterpret_cast<ObjectGetClassFn>(
        xdl_sym(handle, "il2cpp_object_get_class", nullptr));
    if (g_domain_get == nullptr || g_domain_get_assemblies == nullptr ||
        g_assembly_get_image == nullptr || g_class_from_name == nullptr ||
        g_class_get_method_from_name == nullptr || g_class_get_field_from_name == nullptr ||
        g_field_get_offset == nullptr || g_object_get_class == nullptr) {
        LOGE("required IL2CPP resolver exports unavailable for Pikmin %s (%d)",
             kTargetVersion, kTargetVersionCode);
        write_status("resolver-unavailable");
        return false;
    }

    void *list_item_class = find_class("Niantic.Ichigo.Game.Expedition.ListPanel",
                                       "ExpeditionListItem");
    void *callback = list_item_class
        ? g_class_get_method_from_name(list_item_class, "<Start>b__34_0", 1)
        : nullptr;
    void *current_task_field = list_item_class
        ? g_class_get_field_from_name(list_item_class, "currentTask")
        : nullptr;
    auto *click_target = callback ? *reinterpret_cast<void **>(callback) : nullptr;
    g_list_item_current_task_offset = current_task_field
        ? g_field_get_offset(current_task_field) : -1;
    Dl_info target_info{};
    if (click_target == nullptr || g_list_item_current_task_offset < 0 ||
        !dladdr(click_target, &target_info) || target_info.dli_fbase != g_libil2cpp_base) {
        LOGE("Expedition list metadata unavailable for Pikmin %s (%d)",
             kTargetVersion, kTargetVersionCode);
        write_status("list-metadata-unavailable");
        return false;
    }

    A64HookFunction(click_target,
                    reinterpret_cast<void *>(hooked_list_item_on_click),
                    reinterpret_cast<void **>(&g_original_on_click));
    if (g_original_on_click == nullptr) {
        LOGE("hook installation failed");
        write_status("hook-failed");
    } else {
        LOGI("list-item click hook installed for Pikmin %s (%d): target=%p",
             kTargetVersion, kTargetVersionCode, click_target);
        write_status("ready");
#ifdef PCC_UNIFIED
        unified_gps_ready.store(true);
#endif
    }
    return true;
}

}  // namespace

void hack_prepare(const char *game_data_dir, JavaVM *vm) {
    g_game_data_dir = game_data_dir == nullptr ? "" : game_data_dir;
    g_vm = vm;
#ifdef PCC_UNIFIED
    // libil2cpp can be mapped before il2cpp_init has established its domain.
    // The unified caller invokes pcc_gps_install_after_login from a game
    // callback after Nectar has successfully resolved that domain.
    return;
#else
    for (int attempt = 0; attempt < 120; ++attempt) {
        if (install_hook()) return;
        sleep(1);
    }
    LOGE("libil2cpp.so did not load within 120 seconds");
#endif
}

#ifdef PCC_UNIFIED
void pcc_gps_install_after_login() {
    if (unified_gps_ready.load()) return;
    static std::mutex install_mutex;
    std::lock_guard<std::mutex> lock(install_mutex);
    if (!unified_gps_ready.load()) install_hook();
}
#endif

// Called only on the exact-profile-gated Unity UI callbacks, independently of
// the optional display extensions. The existing GPS master switch remains owner.
bool pcc_gps_detail_enabled() {
#ifdef PCC_UNIFIED
    return unified_gps_ready.load() && copy_is_enabled();
#else
    return false;
#endif
}
bool pcc_gps_copy_selected_expedition(void *expedition) {
    return pcc_gps_detail_enabled() && copy_expedition_gps(expedition);
}
