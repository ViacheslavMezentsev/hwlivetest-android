package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class FieldCatalogTest {
    private val fields = listOf(
        Field("/elf/src/a_c/temp", FieldKind.INT32, 0x20000000, expr = "sensors.temp", sourceType = "int16_t", sourceFile = "src/a.c", sizeBits = 16),
        Field("/diag/sensor/batt_v", FieldKind.FLOAT32, 0x20000004),
        Field("/diag/raw/batt_v", FieldKind.FLOAT32, 0x20000008),
    )

    // TC-31: поиск по нескольким словам, типу, адресу; пустые результаты и старые JSON.
    @Test fun filtersAndGroupsPreserveTopics() {
        assertEquals(listOf(fields[0]), FieldCatalog.filter(fields, "SRC/A TEMP", "int16_t"))
        assertEquals(listOf(fields[1]), FieldCatalog.filter(fields, "20000004"))
        assertTrue(FieldCatalog.filter(fields, "batt", "int16_t").isEmpty())
        assertEquals(2, FieldCatalog.group(fields, FieldCatalog.Grouping.SOURCE).size)
        assertEquals(3, FieldCatalog.group(fields, FieldCatalog.Grouping.TOPIC).size)
        assertEquals(1, FieldCatalog.group(fields, FieldCatalog.Grouping.NONE).size)
        assertEquals(listOf(fields[1], fields[2]), FieldCatalog.filter(fields, "batt", "float32"))
        assertEquals("/diag/sensor/batt_v", fields[1].topic)
    }

    @Test fun signedSubwordsUseMaskBeforeSignExtension() {
        val byte = Field("/b", FieldKind.INT32, 0, shift = 24, mask = 0xFF000000, sizeBits = 8)
        val half = Field("/h", FieldKind.INT32, 0, shift = 16, mask = 0xFFFF0000, sizeBits = 16)
        assertEquals(-1.0, byte.decode(-1), 0.0)
        assertEquals(-128.0, byte.decode(0x80000000.toInt()), 0.0)
        assertEquals(0.0, half.decode(0x0000FFFF), 0.0)
        assertEquals(-1.0, half.decode(0xFFFF0000.toInt()), 0.0)
        assertEquals("-1", half.format(-1.0))
        assertEquals(-2147483648.0, Field("/i", FieldKind.INT32, 0).decode(Int.MIN_VALUE), 0.0)
    }

    @Test fun importsCuratedTableWithoutRenamingOrReinterpretingExpressions() {
        val file = AddressesFile.parse("""{"schema":2,"fields":[
          {"topic":"/diag/sensor/batt_v","kind":"float32","address":536870912,
           "expr":"(char*)&sensors + 4","message":"std_msgs/Float32"},
          {"topic":"/diag/pin","kind":"uint32","address":1073872912,"shift":5,"mask":32}
        ]}""")
        assertTrue(file.warnings.isEmpty())
        assertEquals("/diag/sensor/batt_v", file.fields[0].topic)
        assertEquals("(char*)&sensors + 4", file.fields[0].expr)
        assertEquals(1.0, file.fields[1].decode(32), 0.0)
        assertNull(file.fields[1].sourceFile)
    }
}
