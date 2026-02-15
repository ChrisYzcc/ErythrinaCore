#include "trace.h"
#include <cstdio>

FILE *itrace_file = nullptr;
FILE *mtrace_file = nullptr;

#define IRINGBUF_LEN 10000
char irbuf[IRINGBUF_LEN][256];
int irbuf_ptr;
int irbuf_valid[IRINGBUF_LEN];

#define MRINGBUF_LEN 10000
char mrbuf[MRINGBUF_LEN][256];
int mrbuf_ptr;
int mrbuf_valid[MRINGBUF_LEN];

void trace_init() {
    irbuf_ptr = 0;
    mrbuf_ptr = 0;
    init_disasm("riscv64-pc-linux-gnu");
}

char inst_disasm[100];

void itrace(uint64_t pc, uint32_t inst) {
    disassemble(inst_disasm, 100, pc, (uint8_t *)&(inst), 4);
    sprintf(irbuf[irbuf_ptr], "\tPC: 0x%016lx\tInst: 0x%08x\t%s\n", pc, inst, inst_disasm);
    irbuf_valid[irbuf_ptr] = 1;
    irbuf_ptr = (irbuf_ptr + 1) % IRINGBUF_LEN;
}

void mtrace(uint64_t addr, uint64_t data, uint32_t mask, bool is_write, uint64_t pc) {
    sprintf(mrbuf[mrbuf_ptr], "\t%s Addr: 0x%016lx\tData: 0x%016lx\tMask: 0x%08x\tPC: 0x%016lx\n", (is_write ? "Write" : "Read"), addr, data, mask, pc);
    mrbuf_valid[mrbuf_ptr] = 1;
    mrbuf_ptr = (mrbuf_ptr + 1) % MRINGBUF_LEN;
}

void trace_dump() {
    itrace_file = fopen("./build/itrace.log", "w");
    mtrace_file = fopen("./build/mtrace.log", "w");
    if (itrace_file == nullptr || mtrace_file == nullptr) {
        return;
    }
    for (int i = (irbuf_ptr + 1) % IRINGBUF_LEN; i != irbuf_ptr; i = (i + 1) % IRINGBUF_LEN) {
        if (irbuf_valid[i]) {
            fprintf(itrace_file, "%s %s", (i == irbuf_ptr - 1 ? "->" : "  "), irbuf[i]);
        }
    }
    for (int i = (mrbuf_ptr + 1) % MRINGBUF_LEN; i != mrbuf_ptr; i = (i + 1) % MRINGBUF_LEN) {
        if (mrbuf_valid[i]) {
            fprintf(mtrace_file, "%s %s", (i == mrbuf_ptr - 1 ? "->" : "  "), mrbuf[i]);
        }
    }
    fclose(itrace_file);
    fclose(mtrace_file);
}