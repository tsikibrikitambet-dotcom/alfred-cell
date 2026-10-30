# Alfred Cell

Alfred Cell on **Tambet Sarapu** loodud võrguühenduseta Androidi rakendus. See
näitab GPS-koordinaate, UTC-kella ja viimase GPS-fiksi aega ning telefoni
teatatud mobiiliraku signaaliinfot. Punane radar kuvab signaalitugevust, mitte
masti kaugust ega suunda. Lähedal asuvate rakkude info sõltub Androidi
seadmest ja mobiilioperaatorist.

Rakendusel pole internetiõigust ja see ei tee võrgupäringuid. GPS töötab ilma
mobiilse internetita; esimese satelliidifiksi leidmine võib siseruumides kauem
aega võtta.

## Paigaldamine Android-telefoni

1. Laadi fail `alfred-cell.apk` telefoni. Tõsta see sinna USB-kaabli või
	läheduses failijagamisega; pärast GitHubi avaldamist saab selle panna ka
	GitHub Release'i.
2. Ava APK telefoni **Failid** või **Allalaadimised** rakendusest.
3. Kui Android paigalduse blokeerib, vali teates **Seaded** ja luba
	**Tundmatute rakenduste installimine** sellele rakendusele, millega APK-i
	avad. Mine APK-i juurde tagasi ja vajuta **Installi**.
4. Ava Alfred Cell ning luba **Täpne asukoht** ja **Telefon**.
5. Lülita telefoni asukohateenus sisse ja mobiiliraku info jaoks hoia SIM ning
	mobiilside aktiivsena. Kiirema GPS-fiksi saamiseks mine õue, kus taevale on
	avatud vaade. Internetti ega mobiilset andmesidet vaja ei ole.

Uuema APK-i paigaldamiseks olemasoleva peale peab allkirjavõti olema sama.
Selle projekti debug-APK kasutab arvuti kohalikku Androidi debug-võtit.

## Ehitamine

Ava see kaust Android Studios ja oota Gradle'i sünkroonimise lõppu. Või käivita
paigaldatud Gradle'i ja Android SDK-ga selles kaustas `gradle assembleDebug`.
Gradle loob faili `app/build/outputs/apk/debug/app-debug.apk`; telefoni
tõstmiseks nimeta see `alfred-cell.apk`-ks.

## Õigused

- `ACCESS_COARSE_LOCATION` ja `ACCESS_FINE_LOCATION`: GPS-koordinaadid ning
  Androidi mobiilirakuinfo ligipääsureeglid.
- `READ_PHONE_STATE`: ühendatud ja lähedal asuvate rakkude info.

Rakendusel pole tahtlikult `INTERNET`-õigust.