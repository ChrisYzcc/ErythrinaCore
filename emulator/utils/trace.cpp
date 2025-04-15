#include "trace.h"
#include <cstdio>

FILE *trace_file = nullptr;

#define IRINGBUF_LEN 1000
char irbuf[IRINGBUF_LEN][256];
int irbuf_ptr;
int irbuf_valid[IRINGBUF_LEN];

void trace_init() {
    irbuf_ptr = 0;
    init_disasm("riscv32-pc-linux-gnu");
}

char inst_disasm[100];

void trace(uint32_t pc, uint32_t inst) {
    disassemble(inst_disasm, 100, pc, (uint8_t *)&(inst), 4);
    sprintf(irbuf[irbuf_ptr], "\tPC: 0x%08x\tInst: 0x%08x\t%s\n", pc, inst, inst_disasm);
    irbuf_valid[irbuf_ptr] = 1;
    irbuf_ptr = (irbuf_ptr + 1) % IRINGBUF_LEN;
}

void trace_dump() {
    trace_file = fopen("./build/trace.log", "w");
    if (trace_file == nullptr) {
        return;
    }
    for (int i = (irbuf_ptr + 1) % IRINGBUF_LEN; i != irbuf_ptr; i = (i + 1) % IRINGBUF_LEN) {
        if (irbuf_valid[i]) {
            fprintf(trace_file, "%s %s", (i == irbuf_ptr - 1 ? "->" : "  "), irbuf[i]);
        }
    }
    fclose(trace_file);
}