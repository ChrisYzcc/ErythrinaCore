#ifndef __COMMON_H__
#define __COMMON_H__

#include <cstdint>

// Default Inst
static const uint32_t default_inst[] = {
    0x00100093,     // addi x1, x0, 1
    0x00100073      // ebreak
};

// Font
static const char FontYellow[]  = "\033[1;33m";
static const char FontRed[]     = "\033[1;31m";
static const char FontGreen[]   = "\033[1;32m";
static const char FontBlue[]    = "\033[34m";
static const char Restore[]     = "\033[0m";

extern int status;

#endif