#include "difftest.h"
#include "VSimTop__Dpi.h"
#include "emu.h"
#include <cstddef>
#include <cassert>
#include <dlfcn.h>

void (*ref_difftest_memcpy)(paddr_t addr, void *buf, int n, bool direction) = NULL;
void (*ref_difftest_regcpy)(void *dut, bool direction) = NULL;
void (*ref_difftest_exec)(uint32_t n) = NULL;
void (*ref_difftest_init)(int port) = NULL;

const char *diff_ref_so = nullptr; 

void init_difftest(long img_size, int port) {
    
    assert(diff_ref_so != NULL);

    void *handle;
    handle = dlopen(diff_ref_so, RTLD_LAZY);
    assert(handle);

    ref_difftest_memcpy = (void (*)(paddr_t, void *, int, bool))dlsym(handle, "difftest_memcpy");
    assert(ref_difftest_memcpy);

    ref_difftest_regcpy = (void (*)(void *, bool))dlsym(handle, "difftest_regcpy");
    assert(ref_difftest_regcpy);

    ref_difftest_exec = (void (*)(uint32_t n))dlsym(handle, "difftest_exec");
    assert(ref_difftest_exec);

    void (*ref_difftest_init)(int) = (void (*)(int))dlsym(handle, "difftest_init");
    assert(ref_difftest_init);

    ref_difftest_init(port);
    ref_difftest_memcpy(emu->pc_rstvec, guest2host(emu->pc_rstvec), img_size, DIFFTEST_TO_REF);
}

void diff_step() {
    ref_difftest_exec(1);
}

// get commit diff_info from NPC
int get_diff_infos(diff_infos *infos, int req_idx) {
    svLogic valid, rf_wen, mem_wen;
    svLogicVecVal pc, inst, rf_waddr, rf_wdata, mem_addr, mem_data, mem_mask, idx;
    idx.aval = req_idx;

    read_diff_info(
        &idx, &valid, &pc, &inst, &rf_wen, &rf_waddr, &rf_wdata,
        &mem_wen, &mem_addr, &mem_data, &mem_mask
    );

    infos->pc = pc.aval;
    infos->instr = inst.aval;

    infos->rf_wen = rf_wen;
    infos->rf_waddr = rf_waddr.aval;
    infos->rf_wdata = rf_wdata.aval;

    infos->mem_wen = mem_wen;
    infos->mem_addr = mem_addr.aval;
    infos->mem_data = mem_data.aval;
    infos->mem_mask = mem_mask.aval;
    
    return valid;
}