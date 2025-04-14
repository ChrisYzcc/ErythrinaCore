#include "memory.h"
#include "common.h"
#include "emu.h"
#include <cstdint>

uint8_t pmem[MEMSIZE];
uint8_t mrom[MROM_SIZE];
uint8_t sram[SRAM_SIZE];
uint8_t flash[FLASH_SIZE];

long init_mem(char *img){
    if (img == nullptr) {
        printf("Use default image.\n");
        memcpy(guest2host(emu->pc_rstvec), default_inst, sizeof(default_inst));
        return 8;
    }

    FILE *fp = fopen(img, "rb");
    assert(fp != NULL);

    fseek(fp, 0, SEEK_END);
    uint32_t size = ftell(fp);

    printf("The image is %s, size = %d\n", img, size);
    assert(size < MEMSIZE);

    fseek(fp, 0, SEEK_SET);
    int ret = fread(guest2host(emu->pc_rstvec), size, 1, fp);
    assert(ret == 1);

    fclose(fp);
    return size;
}

uint8_t* guest2host(paddr_t paddr){
    if (in_pmem(paddr))
        return pmem + paddr - MEMBASE;
    emu->trap(TRAP_MEM_ERR, paddr);
    return nullptr;
}

uint32_t host_read(void *addr){
    return *(uint32_t *)addr;
}

uint32_t host_write(void *addr, uint32_t data, uint32_t mask){
    uint32_t real_mask = 0;
    for (int i = 0; i < 4; i++){
        if (mask & 1){
            real_mask |= 0xff << (i * 8);
        }
        mask >>= 1;
    }
    uint32_t real_data = *(uint32_t *)addr & (~real_mask) | data & real_mask;
    *(uint32_t *)addr = real_data;
    return real_data;
}

uint32_t pmem_read(paddr_t addr) {
    uint32_t res = 0;
    uint8_t *p = guest2host(addr);

    if (p != nullptr) {
        res = host_read(p);
    }

    return res;
}

uint32_t pmem_write(paddr_t addr, uint32_t data, uint32_t mask) {
    uint32_t res = 0;
    uint8_t *p = guest2host(addr);

    if (p != nullptr) {
        res = host_write(p, data, mask);
    }

    return res;
}