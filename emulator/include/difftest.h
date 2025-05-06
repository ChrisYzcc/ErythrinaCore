#ifndef __DIFFTEST_H__
#define __DIFFTEST_H__

#include "memory.h"

#define COMMIT_WIDTH 5

extern const char *diff_ref_so;

struct diff_infos {
    uint32_t pc;
    uint32_t instr;

    bool rf_wen;
    uint32_t rf_waddr;
    uint32_t rf_wdata;

    bool mem_en;
    uint32_t mem_addr;
    uint32_t mem_data;
    uint32_t mem_mask;
};

void init_difftest(long img_size, int port);

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

extern void (*ref_difftest_memcpy)(paddr_t addr, void *buf, int n, bool direction);
extern void (*ref_difftest_regcpy)(void *dut, bool direction);
extern void (*ref_difftest_exec)(uint32_t n);
extern void (*ref_difftest_init)(int port);

extern void diff_step();

extern int get_diff_infos(diff_infos *infos, int req_idx);

#endif