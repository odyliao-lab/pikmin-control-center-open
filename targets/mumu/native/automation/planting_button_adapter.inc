#include "planting_button_policy.h"
namespace emu_planting_button {
using ClassParent=void *(*)(void *);
ClassParent parent{};
bool tried{};
bool compatible(void *object) {
    if (!object || !object_get_class) return false;
    void *expected=find_class("UnityEngine.UI","Button");
    void *actual=object_get_class(object);
    if (!expected || !actual) return false;
    if (expected==actual) return true;
    if (!tried) {
        tried=true;
        void *lib=xdl_open("libil2cpp.so",XDL_DEFAULT);
        if (lib) parent=reinterpret_cast<ClassParent>(xdl_sym(lib,"il2cpp_class_get_parent",nullptr));
    }
    if (!parent) return false;
    return pcc_planting_button::compatible(actual,expected,[](void *klass){return parent(klass);});
}
}
