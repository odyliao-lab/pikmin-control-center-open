// Only the SHA-reviewed v157 nullable Task field may certify controller cleanup.
// A missing/invalid metadata read is NOT a null field, nor a successful stop.
namespace emu_planting_tx {
bool read_terminal_controller_result(void *&current) {
    current=nullptr;
    if(!runtime_metadata_ready || !emu_late::bound || emu_late::revoked ||
       !planting_controller || !object_get_class || !class_get_field ||
       !field_get_offset || !field_get_type || !field_get_value ||
       !class_from_type || !emu_late::flags || !emu_late::type_code || !emu_late::value_type)
        return false;
    void *controller_class=find_class("Niantic.Ichigo.Game.Flowers","FlowerPlantingController");
    void *task_class=find_class("System.Threading.Tasks","Task");
    if(!controller_class || !task_class || object_get_class(planting_controller)!=controller_class)
        return false;
    void *field=class_get_field(controller_class,"plantingResultTask");
    if(!field || field_get_offset(field)!=0x160 || (emu_late::flags(field)&0x10))return false;
    void *type=field_get_type(field);
    if(!type || emu_late::type_code(type)!=0x12 || class_from_type(type)!=task_class ||
       emu_late::value_type(task_class))return false;
    field_get_value(planting_controller,field,&current);
    return true;
}
}
