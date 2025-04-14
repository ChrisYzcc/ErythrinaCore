#ifndef __ISA_H__
#define __ISA_H__

#include <cstdint>

#define ARCH_REG_NUM 32
#define PHY_REG_NUM 64

typedef struct{
    uint32_t gpr[ARCH_REG_NUM];
    uint32_t pc;
}CPUState;

extern const char *regs[];

extern char *get_regname(int index);
extern int get_regidx(const char *name);

#endif