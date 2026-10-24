#include <stdint.h>
#include <stdbool.h>
typedef struct { float voltage_batt; float current; int16_t temp; uint8_t flags; bool ok; } Cached;
typedef struct { uint32_t count; Cached cached_data; float hist[3]; } SensorManager;
volatile SensorManager sensors;
volatile uint32_t uwTick;
static int32_t offset = -5;
double big = 1.5;
const uint32_t table[4] = {1,2,3,4};
uint8_t buf[100];
int main(void){ sensors.count++; offset++; return (int)sensors.cached_data.voltage_batt + (int)(table[uwTick&3] + buf[0] + offset) + (int)big; }
void Reset_Handler(void){ main(); for(;;); }
__attribute__((section(".isr_vector"))) const void* vectors[2] = {(void*)0x20020000, (void*)Reset_Handler};
