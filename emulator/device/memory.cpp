#include "memory.h"
#include "common.h"
#include "cosimulation.h"
#include "device.h"
#include "emu.h"
#include <cstdint>
#include <iostream>

uint8_t pmem[MEMSIZE];
uint8_t mrom[MROM_SIZE];
uint8_t sram[SRAM_SIZE];
uint8_t flash[FLASH_SIZE];

CoDRAMsim3 *dram = nullptr;

const char *config_file = nullptr;
const char *out_dir = nullptr;

long init_mem(char *img){
    std::cout << "[INFO] Initialize memory" << std::endl;

    // Initialize the DRAMSim3
#ifdef DRAMSIM3_CONFIG
config_file = DRAMSIM3_CONFIG;
#endif

#ifdef DRAMSIM3_OUTDIR
out_dir = DRAMSIM3_OUTDIR;
#endif
    std::cout << "DRAMSIM3 config: " << config_file << std::endl;
    std::cout << "DRAMSIM3 outdir: " << out_dir << std::endl;
    dram = new ComplexCoDRAMsim3(config_file, out_dir);

    if (img == nullptr) {
        printf("Use default image.\n");
        memcpy(guest2host(PC_RSTVEC), default_inst, sizeof(default_inst));
        return 8;
    }

    FILE *fp = fopen(img, "rb");
    assert(fp != NULL);

    fseek(fp, 0, SEEK_END);
    uint32_t size = ftell(fp);

    printf("The image is %s, size = %d\n", img, size);
    assert(size < MEMSIZE);

    fseek(fp, 0, SEEK_SET);
    int ret = fread(guest2host(PC_RSTVEC), size, 1, fp);
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
    if (is_device(addr) != -1) {
        return device_read(addr);
    }

    uint32_t res = 0;
    uint8_t *p = guest2host(addr);

    if (p != nullptr) {
        res = host_read(p);
    }

    return res;
}

uint32_t pmem_write(paddr_t addr, uint32_t data, uint32_t mask) {
    if (is_device(addr) != -1) {
        return device_write(addr, data, mask);
    }
    
    uint32_t res = 0;
    uint8_t *p = guest2host(addr);

    if (p != nullptr) {
        res = host_write(p, data, mask);
    }

    return res;
}