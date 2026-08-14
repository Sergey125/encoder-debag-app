#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include "driver/pulse_cnt.h"

// ==================== НАСТРОЙКИ ====================
#define REQUIRE_ENCRYPTION   0   // 1 — требовать PIN 123456 при подписке на нотификации

#define STEPS_PER_DETENT     4   // 4 — детентный энкодер (KY-040/EC11); 1 — сырые импульсы (600 PPR)
#define PULSES_PER_REV       600 // счёт идёт по кругу: 0..599, затем снова 0

const char* DEVICE_NAME = "encoder";

const int pinCLK = 3;
const int pinDT  = 4;

const unsigned long SEND_INTERVAL_MS = 10;    // как часто проверяем накопленные шаги энкодера и шлём BLE-нотификацию (мс)
const unsigned long LED_OFF_DELAY_MS = 150;   // сколько держать красный светодиод после последнего замеченного шага энкодера, прежде чем погасить (мс)

#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

#define RGB_LED_PIN 8

#define PCNT_HIGH_LIMIT       1000
#define PCNT_LOW_LIMIT       -1000
#define PCNT_GLITCH_FILTER_NS  1000
// =====================================================

Adafruit_NeoPixel rgbLed(1, RGB_LED_PIN, NEO_GRB + NEO_KHZ800);

NimBLEServer*         pServer           = nullptr;
NimBLECharacteristic* pTxCharacteristic = nullptr;

volatile bool     deviceConnected = false;
volatile bool     notifyEnabled   = false;
volatile uint16_t connHandle      = 0;
bool     connParamsPending = false;
uint32_t connectedAt       = 0;

// --- PCNT ---
pcnt_unit_handle_t    pcntUnit  = nullptr;
pcnt_channel_handle_t pcntChanA = nullptr;
pcnt_channel_handle_t pcntChanB = nullptr;

portMUX_TYPE pcntMux = portMUX_INITIALIZER_UNLOCKED;
volatile int64_t pcntOverflowAccum = 0;

int64_t lastRawTotal = 0;
int32_t stepSubAccum = 0;

static bool IRAM_ATTR pcntOnReach(pcnt_unit_handle_t unit, const pcnt_watch_event_data_t *edata, void *user_ctx) {
  portENTER_CRITICAL_ISR(&pcntMux);
  pcntOverflowAccum += edata->watch_point_value;
  portEXIT_CRITICAL_ISR(&pcntMux);
  return false;
}

void setupPcnt() {
  pcnt_unit_config_t unitConfig = {};
  unitConfig.high_limit = PCNT_HIGH_LIMIT;
  unitConfig.low_limit  = PCNT_LOW_LIMIT;
  ESP_ERROR_CHECK(pcnt_new_unit(&unitConfig, &pcntUnit));

  pcnt_glitch_filter_config_t filterConfig = {};
  filterConfig.max_glitch_ns = PCNT_GLITCH_FILTER_NS;
  ESP_ERROR_CHECK(pcnt_unit_set_glitch_filter(pcntUnit, &filterConfig));

  pcnt_chan_config_t chanAConfig = {};
  chanAConfig.edge_gpio_num  = pinCLK;
  chanAConfig.level_gpio_num = pinDT;
  ESP_ERROR_CHECK(pcnt_new_channel(pcntUnit, &chanAConfig, &pcntChanA));

  pcnt_chan_config_t chanBConfig = {};
  chanBConfig.edge_gpio_num  = pinDT;
  chanBConfig.level_gpio_num = pinCLK;
  ESP_ERROR_CHECK(pcnt_new_channel(pcntUnit, &chanBConfig, &pcntChanB));

  // Стандартная x4-квадратурная схема декодирования (как в официальном
  // примере Espressif peripherals/pcnt/rotary_encoder). Если направление
  // вращения окажется перевёрнутым — поменяйте местами INCREASE/DECREASE
  // в обеих строках edge_action ниже.
  ESP_ERROR_CHECK(pcnt_channel_set_edge_action(pcntChanA,
      PCNT_CHANNEL_EDGE_ACTION_DECREASE, PCNT_CHANNEL_EDGE_ACTION_INCREASE));
  ESP_ERROR_CHECK(pcnt_channel_set_level_action(pcntChanA,
      PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE));

  ESP_ERROR_CHECK(pcnt_channel_set_edge_action(pcntChanB,
      PCNT_CHANNEL_EDGE_ACTION_INCREASE, PCNT_CHANNEL_EDGE_ACTION_DECREASE));
  ESP_ERROR_CHECK(pcnt_channel_set_level_action(pcntChanB,
      PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE));

  ESP_ERROR_CHECK(pcnt_unit_add_watch_point(pcntUnit, PCNT_HIGH_LIMIT));
  ESP_ERROR_CHECK(pcnt_unit_add_watch_point(pcntUnit, PCNT_LOW_LIMIT));

  pcnt_event_callbacks_t cbs = {};
  cbs.on_reach = pcntOnReach;
  ESP_ERROR_CHECK(pcnt_unit_register_event_callbacks(pcntUnit, &cbs, nullptr));

  ESP_ERROR_CHECK(pcnt_unit_enable(pcntUnit));
  ESP_ERROR_CHECK(pcnt_unit_clear_count(pcntUnit));
  ESP_ERROR_CHECK(pcnt_unit_start(pcntUnit));
}

int64_t readPcntTotal() {
  int raw = 0;
  pcnt_unit_get_count(pcntUnit, &raw);
  portENTER_CRITICAL(&pcntMux);
  int64_t overflow = pcntOverflowAccum;
  portEXIT_CRITICAL(&pcntMux);
  return overflow + raw;
}

// --- Индикация ---
void blinkColor(int times, uint8_t r, uint8_t g, uint8_t b) {
  for (int i = 0; i < times; i++) {
    rgbLed.setPixelColor(0, rgbLed.Color(r, g, b));
    rgbLed.show();
    delay(200);
    rgbLed.setPixelColor(0, rgbLed.Color(0, 0, 0));
    rgbLed.show();
    delay(200);
  }
}

bool     ledRedOn             = false;
uint32_t lastRotationActivity = 0;

void updateRotationLed(bool stepsHappened, uint32_t now) {
  if (stepsHappened) {
    lastRotationActivity = now;
    if (!ledRedOn) {
      ledRedOn = true;
      rgbLed.setPixelColor(0, rgbLed.Color(255, 0, 0));
      rgbLed.show();
    }
  } else if (ledRedOn && now - lastRotationActivity >= LED_OFF_DELAY_MS) {
    ledRedOn = false;
    rgbLed.setPixelColor(0, rgbLed.Color(0, 0, 0));
    rgbLed.show();
  }
}

void sendMsg(const char* payload) {
  if (!deviceConnected || !notifyEnabled || pTxCharacteristic == nullptr) return;
  char buf[32];
  int n = snprintf(buf, sizeof(buf), "%s\n", payload);
  if (n <= 0) return;
  if (n >= (int)sizeof(buf)) n = sizeof(buf) - 1;
  pTxCharacteristic->setValue((uint8_t*)buf, n);
  pTxCharacteristic->notify();
}

void resetEncoder() {
  pcnt_unit_clear_count(pcntUnit);
  portENTER_CRITICAL(&pcntMux);
  pcntOverflowAccum = 0;
  portEXIT_CRITICAL(&pcntMux);
  lastRawTotal = 0;
  stepSubAccum = 0;
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* srv, NimBLEConnInfo& info) override {
    connHandle        = info.getConnHandle();
    deviceConnected   = true;
    connectedAt       = millis();
    connParamsPending = true;
    resetEncoder();
  }
  void onDisconnect(NimBLEServer* srv, NimBLEConnInfo& info, int reason) override {
    deviceConnected   = false;
    notifyEnabled     = false;
    connParamsPending = false;
    NimBLEDevice::startAdvertising();
  }
};

class CharCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic* chr, NimBLEConnInfo& info, uint16_t subValue) override {
    notifyEnabled = (subValue & 0x0001) != 0;
  }
};

void setup() {
  rgbLed.begin();
  rgbLed.setBrightness(50);
  rgbLed.show();

  pinMode(pinCLK, INPUT_PULLUP);
  pinMode(pinDT,  INPUT_PULLUP);

  setupPcnt();

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

#if REQUIRE_ENCRYPTION
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityPasskey(123456);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
#else
  NimBLEDevice::setSecurityAuth(false, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
#endif

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  uint32_t props = NIMBLE_PROPERTY::NOTIFY;
#if REQUIRE_ENCRYPTION
  props |= NIMBLE_PROPERTY::READ_ENC;
#endif

  pTxCharacteristic = pService->createCharacteristic(CHARACTERISTIC_UUID_TX, props);
  pTxCharacteristic->setCallbacks(new CharCallbacks());
  pService->start();

  // Основной пакет: флаги (3) + 128-битный UUID (18) + имя "encoder" (9) = 30 из 31 байта.
  // UUID лежит именно здесь, а не в scan response — так фильтр
  // setServiceUuid() в Android-приложении срабатывает надёжно.
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();

  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.setCompleteServices(NimBLEUUID(SERVICE_UUID));
  advData.setName(DEVICE_NAME);
  adv->setAdvertisementData(advData);

  adv->setMinInterval(160);   // 160 * 0.625 мс = 100 мс
  adv->setMaxInterval(240);   // 240 * 0.625 мс = 150 мс

  adv->enableScanResponse(false);

  bool advOk = adv->start();

  // Зелёный x3 — реклама пошла. Красный x5 — реклама НЕ стартовала.
  blinkColor(advOk ? 3 : 5, advOk ? 0 : 255, advOk ? 255 : 0, 0);
}

void loop() {
  const uint32_t now = millis();

  if (deviceConnected && connParamsPending && now - connectedAt > 500) {
    pServer->updateConnParams(connHandle, 6, 12, 0, 400);
    connParamsPending = false;
  }

  static uint32_t lastSendTime = 0;
  bool stepsHappened = false;

  if (now - lastSendTime >= SEND_INTERVAL_MS) {
    lastSendTime = now;

    int64_t total    = readPcntTotal();
    int32_t rawDelta = (int32_t)(total - lastRawTotal);
    lastRawTotal     = total;

    stepSubAccum += rawDelta;

    int32_t steps = 0;
    while (stepSubAccum >= STEPS_PER_DETENT) {
      steps++;
      stepSubAccum -= STEPS_PER_DETENT;
    }
    while (stepSubAccum <= -STEPS_PER_DETENT) {
      steps--;
      stepSubAccum += STEPS_PER_DETENT;
    }

    if (steps != 0) {
      char tmp[24];
      snprintf(tmp, sizeof(tmp), "ENC:%ld", (long)steps);
      sendMsg(tmp);
      stepsHappened = true;
    }
  }

  updateRotationLed(stepsHappened, now);
}
