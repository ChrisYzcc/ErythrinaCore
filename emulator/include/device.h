#ifndef __DEVICE_H__
#define __DEVICE_H__

#include <memory.h>
#define DEV_NUM 100

#define SERIAL_BASE 0xa00003f8
#define RTC_BASE    0xa0000048

extern void init_device();
extern void init_serial();
extern void init_timer();

extern int is_device(paddr_t addr);
extern uint32_t device_read(paddr_t addr);
extern uint32_t device_write(paddr_t addr, uint32_t data, uint32_t mask);

#endif