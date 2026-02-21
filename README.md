# StreamSleep 🌙

Aplikacja Android do automatycznego zamykania Netflix / Prime Video / Disney+
i gaszenia ekranu po zadanym czasie. Przeznaczona dla Samsung Galaxy S24 Ultra (Android 14).

---

## Funkcje

| Funkcja | Opis |
|---|---|
| Wybór aplikacji | Netflix, Prime Video, Disney+ |
| Opcje czasu | 1, 15, 30, 45, 60 minut |
| Timer overlay | Odliczanie w prawym dolnym rogu (mała czcionka, półprzezroczyste) |
| Tryb tła | Działa jako Foreground Service, nie przeszkadza w oglądaniu |
| Blokada ekranu | Po wygaśnięciu timera – ekran gaśnie (tryb snu) |
| Powiadomienie | Pasek powiadomień z aktualnym odliczaniem i przyciskiem "Zatrzymaj" |

---

## Instalacja w Android Studio

1. Sklonuj / rozpakuj projekt
2. Otwórz folder `StreamSleep` w Android Studio (File → Open)
3. Poczekaj na synchronizację Gradle
4. Podłącz S24 Ultra kablem USB (Developer Mode włączony)
5. Kliknij **Run ▶**

---

## Konfiguracja po instalacji (OBOWIĄZKOWE)

### 1. Usługi ułatwień dostępu
Aplikacja MUSI mieć włączoną usługę dostępności, aby móc zamykać inne aplikacje i blokować ekran.

**Ustawienia → Ułatwienia dostępu → Zainstalowane aplikacje → StreamSleep → Włącz**

Lub bezpośrednio przez aplikację: przy pierwszym uruchomieniu pojawi się monit.

### 2. Wyświetlanie na wierzchu innych aplikacji
Wymagane dla nakładki z odliczaniem (timer overlay).

**Ustawienia → Aplikacje → StreamSleep → Wyświetlaj na wierzchu innych aplikacji → Zezwól**

---

## Jak działa zamykanie aplikacji

Aplikacja korzysta z **Android Accessibility Service** + `GLOBAL_ACTION_HOME` i `GLOBAL_ACTION_LOCK_SCREEN`:

1. Po upływie timera — usługa wysyła broadcast do `AppCloseAccessibilityService`
2. Serwis dostępności nakazuje systemowi naciśnięcie przycisku Home (minimalizacja bieżącej aplikacji)
3. Następnie blokuje ekran (`GLOBAL_ACTION_LOCK_SCREEN`)

> **Uwaga**: Samsung One UI może wymagać dodatkowego zezwolenia „Wykonywanie gestów" dla Accessibility Service. Aplikacja nie wymaga root.

---

## Architektura

```
MainActivity.kt              – UI, wybór aplikacji i czasu
SleepTimerService.kt         – Foreground Service (CountDownTimer, powiadomienie)
OverlayTimerView.kt          – Nakładka System Alert Window (timer w rogu ekranu)
AppCloseAccessibilityService.kt – Wykonuje akcje systemowe (Home, Lock Screen)
```

---

## Uprawnienia w AndroidManifest

| Uprawnienie | Cel |
|---|---|
| `FOREGROUND_SERVICE` | Praca w tle |
| `SYSTEM_ALERT_WINDOW` | Nakładka z timerem |
| `BIND_ACCESSIBILITY_SERVICE` | Zamykanie aplikacji, blokada ekranu |

---

## Rozwiązywanie problemów

**Aplikacja nie zamyka się po timerze**
→ Sprawdź, czy Usługi ułatwień dostępu są włączone dla StreamSleep

**Brak nakładki z timerem**
→ Sprawdź Wyświetlanie na wierzchu innych aplikacji

**Samsung One UI może uśpić serwis**
→ Ustawienia → Bateria → StreamSleep → Optymalizacja bez ograniczeń
