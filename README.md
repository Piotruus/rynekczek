# Rynek (Paper 1.21+ / Vault)

Budowanie: `mvn clean package` -> `target/Rynek-1.0.0.jar` -> wrzuć do `plugins/` (wymagany Vault + plugin ekonomii).

Komendy: `/ah` lub `/rynek` (otwiera rynek), `/ah open`, `/rynek otworz`, `/ah sell <cena>` (np. 1000, 2.5k, 1m), `/ah reload`.
Uprawnienia: rynek.use, rynek.sell (domyślnie wszyscy), rynek.admin (reload), rynek.limit.<n> (limit ofert).

Kategorie / StormItemy: edytuj sekcję `categories` w config.yml (reguły: materials, suffix, contains, name, lore, text,
all-text oraz pdc - klucz PersistentDataContainer, np. "stormitemy:id").

Wyszukiwanie: polskie nazwy, bez polskich znaków, literówki, początki słów, filtry cen (<5000 >100), trafność.
