#include "device.h"
#include <cassert>
#include <cstdint>
#include <cstring>
#include <memory.h>
#include <cstdio>
#include <cstdlib>
#include <sys/time.h>

typedef void (*call_back_func)(uint32_t offset, int operation);

struct Device {
    char name[10];
    paddr_t device_base;
    uintptr_t data_ptr;
    call_back_func callback;
};

int dev_idx = 0;
Device dev[DEV_NUM];

int is_device(paddr_t addr) {
    for (int i = 0; i < dev_idx; i++) {
        if ((addr >= dev[i].device_base) && (addr < dev[i].device_base + 16)) {
            return i;
        }
    }
    return -1;
}

uint32_t device_read(paddr_t addr) {
    int idx = is_device(addr);
    if (idx == -1) {
        return 0;
    }
    uint32_t offset = addr - dev[idx].device_base;
    dev[idx].callback(offset, 0); // read operation

    uint32_t data = *(uint32_t *)(dev[idx].data_ptr + offset);
    return data;
}

uint32_t device_write(paddr_t addr, uint32_t data, uint32_t mask) {
    int idx = is_device(addr);
    if (idx == -1) {
        return 0;
    }
    uint32_t offset = addr - dev[idx].device_base;

    uint32_t *data_ptr = (uint32_t *)(dev[idx].data_ptr + offset);
    uint32_t real_mask = 0;
    for (int i = 0; i < 4; i++) {
        if (mask & 1) {
            real_mask |= 0xff << (i * 8);
        }
        mask >>= 1;
    }
    uint32_t real_data = *data_ptr & (~real_mask) | data & real_mask;
    *data_ptr = real_data;

    dev[idx].callback(offset, 1); // write operation
    return real_data;
}

// serial
static uint8_t *serial_base = nullptr;
void serial_handler(uint32_t offset, int operation) {
    assert(operation == 1); // only write
    putchar(serial_base[offset]);
}

void init_serial() {
    serial_base = (uint8_t *)malloc(8);
    strcpy(dev[dev_idx].name, "serial");
    dev[dev_idx].device_base = SERIAL_BASE;
    dev[dev_idx].data_ptr = (uintptr_t)serial_base;
    dev[dev_idx].callback = serial_handler;
    dev_idx++;
}

// timer
uint32_t *rtc_base = nullptr;

uint64_t boot_time = 0;
uint64_t gettime(){
    struct timeval tv;

    gettimeofday(&tv, NULL);

    uint64_t t = tv.tv_sec * 1000000 + tv.tv_usec;

    if (boot_time == 0)
        boot_time = t;
    return t - boot_time;
}

void rtc_handler(uint32_t offset, int operation){
    assert(operation == 0); // only read
    if (offset == 0){
        uint64_t t = gettime();
        rtc_base[0] = (uint32_t)(t & 0xffffffff);
        rtc_base[1] = (uint32_t)(t >> 32);
    }
}

void init_timer(){
    gettime();
    rtc_base = (uint32_t *)malloc(8);
    strcpy(dev[dev_idx].name, "rtc");
    dev[dev_idx].device_base = RTC_BASE;
    dev[dev_idx].data_ptr = (uintptr_t)rtc_base;
    dev[dev_idx].callback = rtc_handler;
    dev_idx++;
}

void init_device() {
    init_serial();
    init_timer();
    for (int i = 0; i < dev_idx; i++) {
        printf("[Info] Device %s: 0x%08x\n", dev[i].name, dev[i].device_base);
    }
}