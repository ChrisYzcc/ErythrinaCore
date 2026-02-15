#ifndef __TRACE_H__
#define __TRACE_H__

#include <cstdint>

extern void trace_init();
extern void itrace(uint64_t pc, uint32_t inst);
extern void mtrace(uint64_t addr, uint64_t data, uint32_t mask, bool is_write, uint64_t pc);
extern void trace_dump();

extern "C" void init_disasm(const char *triple);
extern "C" void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);

#endif