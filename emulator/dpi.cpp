#include "VSimTop__Dpi.h"
#include "dpi.h"

extern "C" void halt(int reason) {

}

extern "C" int mem_read(int paddr) {
    return 0;
}

extern "C" void mem_write(int paddr, const svBitVecVal* mask, int data) {

}

extern "C" void read_diff_info(const svLogicVecVal *idx, svLogic *valid, svLogicVecVal *pc, svLogicVecVal *inst, svLogic *rf_wen, svLogicVecVal *rf_waddr, svLogicVecVal *rf_wdata, svLogic *mem_wen, svLogicVecVal *mem_addr, svLogicVecVal *mem_data, svLogicVecVal *mem_mask){
    
}

extern "C" void peek_arch_rat(const svLogicVecVal *a_reg, svLogicVecVal *p_reg) {
    
}

extern "C" void peek_regfile(const svLogicVecVal *reg_idx, svLogicVecVal *reg_value) {
    
}