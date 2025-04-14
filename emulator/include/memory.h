#ifndef __MEMORY_H__
#define __MEMORY_H__

#include <cstdint>
typedef uint32_t paddr_t;

#define MEMBASE     0x80000000               
#define MEMSIZE     0x8000000

#define FLASH_BASE  0x30000000
#define FLASH_SIZE  0x100000

#define MROM_BASE   0x20000000
#define MROM_SIZE   0x1000

#define SRAM_BASE   0x0f000000
#define SRAM_SIZE   0x2000

extern uint8_t pmem[MEMSIZE];
extern uint8_t mrom[MROM_SIZE];
extern uint8_t sram[SRAM_SIZE];
extern uint8_t flash[FLASH_SIZE];

static inline bool in_pmem(paddr_t addr){
    return addr >= MEMBASE && addr - MEMBASE < MEMSIZE;
}

static inline bool in_mrom(paddr_t addr){
    return addr >= MROM_BASE && addr - MROM_BASE < MROM_SIZE;
}

static inline bool in_sram(paddr_t addr){
    return addr >= SRAM_BASE && addr - SRAM_BASE < SRAM_SIZE;
}

static inline bool in_flash(paddr_t addr){
    return addr >= FLASH_BASE && addr - FLASH_BASE < FLASH_SIZE;
}

extern long init_mem(char *img);

extern uint8_t *guest2host(paddr_t paddr);
extern uint32_t pmem_read(paddr_t addr);
extern uint32_t pmem_write(paddr_t addr, uint32_t data, uint32_t mask);

#endif