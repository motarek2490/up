package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

fun DocumentSnapshot.getSafeString(field: String): String? {
    return try {
        this.getString(field)
    } catch (e: Throwable) {
        val obj = this.get(field)
        obj?.toString()
    }
}

fun DocumentSnapshot.getFlexTimestamp(field: String): Timestamp? {
    try {
        val ts = this.getTimestamp(field)
        if (ts != null) return ts
    } catch (ignored: Throwable) {}

    val str = this.getSafeString(field)
    if (!str.isNullOrBlank()) {
        try {
            val sdfIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val d = sdfIso.parse(str)
            if (d != null) return Timestamp(d)
        } catch (ignored: Throwable) {}

        try {
            val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val d2 = sdfDate.parse(str)
            if (d2 != null) return Timestamp(d2)
        } catch (ignored: Throwable) {}
    }
    return null
}

data class Order(
    val id: String = "",
    val orderNumber: String = "",
    val clientName: String = "غير محدد",
    val phoneNumber: String = "",
    val vodafoneCashNumber: String = "",
    val packageId: String = "",
    val packageName: String = "غير محدد",
    val amount: Double = 0.0,
    val status: String = "pending", // "pending", "approved", "rejected"
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val invitationId: String = "",
    val receiptImageUrl: String? = null,
    val rejectionReason: String? = null,
    val hostCode: String? = null
) {
    val isPending: Boolean get() = status.equals("pending", ignoreCase = true)
    val isApproved: Boolean get() = status.equals("approved", ignoreCase = true)
    val isRejected: Boolean get() = status.equals("rejected", ignoreCase = true)

    val formattedStatusArabic: String get() = when (status.lowercase()) {
        "pending" -> "قيد المراجعة"
        "approved" -> "معتمد ومفعّل"
        "rejected" -> "مرفوض"
        else -> status
    }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): Order? {
            val data = doc.data ?: return null
            val rawClient = doc.getSafeString("customerName") 
                ?: doc.getSafeString("clientName") 
                ?: doc.getSafeString("name") 
                ?: doc.getSafeString("fullName")
                ?: doc.getSafeString("userName")
                ?: doc.getSafeString("customer")
            val client = if (rawClient.isNullOrBlank()) "غير محدد" else rawClient.trim()

            val rawPackage = doc.getSafeString("planTier") 
                ?: doc.getSafeString("packageName") 
                ?: doc.getSafeString("packageTier")
                ?: doc.getSafeString("tier")
                ?: doc.getSafeString("plan")
            val pkg = if (rawPackage.isNullOrBlank()) "غير محدد" else rawPackage.trim()

            val amountVal = when (val rawAmt = data["amount"] ?: data["totalAmount"] ?: data["price"]) {
                is Number -> rawAmt.toDouble()
                is String -> rawAmt.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }

            val orderId = doc.getSafeString("id") ?: doc.id
            val orderNum = doc.getSafeString("orderNumber") ?: doc.getSafeString("orderId") ?: orderId

            val statusVal = doc.getSafeString("status") 
                ?: doc.getSafeString("orderStatus") 
                ?: "pending"

            return Order(
                id = doc.id,
                orderNumber = orderNum,
                clientName = client,
                phoneNumber = doc.getSafeString("customerPhone") ?: doc.getSafeString("phoneNumber") ?: doc.getSafeString("phone") ?: doc.getSafeString("mobile") ?: "",
                vodafoneCashNumber = doc.getSafeString("vodafoneCashSender") ?: doc.getSafeString("vodafoneCashNumber") ?: doc.getSafeString("walletNumber") ?: doc.getSafeString("senderPhone") ?: "",
                packageId = doc.getSafeString("packageId") ?: "",
                packageName = pkg,
                amount = amountVal,
                status = statusVal,
                createdAt = doc.getFlexTimestamp("createdAt") ?: doc.getFlexTimestamp("date"),
                updatedAt = doc.getFlexTimestamp("updatedAt"),
                invitationId = doc.getSafeString("invitationId") ?: doc.getSafeString("slug") ?: "",
                receiptImageUrl = doc.getSafeString("receiptImageUrl") ?: doc.getSafeString("receiptUrl") ?: doc.getSafeString("paymentReceipt"),
                rejectionReason = doc.getSafeString("rejectionReason") ?: doc.getSafeString("rejectReason"),
                hostCode = doc.getSafeString("hostCode") ?: doc.getSafeString("accessCode")
            )
        }
    }
}

data class Invitation(
    val id: String = "",
    val slug: String = "",
    val groomName: String = "",
    val brideName: String = "",
    val title: String = "",
    val eventDate: String = "",
    val status: String = "draft", // "published", "pending", "draft", "expired", "terminated"
    val createdAt: Timestamp? = null,
    val expiresAt: Timestamp? = null,
    val hostCode: String? = null,
    val venueName: String = "",
    val packageTier: String = "غير محدد",
    val orderId: String = "",
    val rsvpCount: Long = 0,
    val hostPortalUrl: String = ""
) {
    val coupleTitle: String
        get() = try {
            if (groomName.isNotBlank() && brideName.isNotBlank()) {
                "$groomName & $brideName"
            } else if (title.isNotBlank()) {
                title
            } else {
                "دعوة بدون عنوان (${slug.ifBlank { id }})"
            }
        } catch (e: Throwable) {
            "دعوة بدون عنوان"
        }

    val formattedStatusArabic: String 
        get() = try {
            when (status.lowercase()) {
                "published" -> "منشورة ونشطة"
                "pending", "pending_approval" -> "قيد المعالجة"
                "draft" -> "مسودة"
                "expired" -> "منتهية الصلاحية"
                "terminated", "rejected" -> "ملغاة"
                else -> status
            }
        } catch (e: Throwable) {
            "غير معروف"
        }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): Invitation? {
            val data = doc.data ?: return null

            @Suppress("UNCHECKED_CAST")
            val eventDetailsMap = doc.get("eventDetails") as? Map<String, Any?>

            val groomNameVal = eventDetailsMap?.get("groomName") as? String
                ?: doc.getSafeString("groomName")
                ?: doc.getSafeString("groom")
                ?: ""

            val brideNameVal = eventDetailsMap?.get("brideName") as? String
                ?: doc.getSafeString("brideName")
                ?: doc.getSafeString("bride")
                ?: ""

            val venueVal = eventDetailsMap?.get("venueName") as? String
                ?: doc.getSafeString("venueName")
                ?: eventDetailsMap?.get("address") as? String
                ?: doc.getSafeString("location")
                ?: ""

            val dateVal = eventDetailsMap?.get("eventDate") as? String
                ?: doc.getSafeString("eventDate")
                ?: doc.getSafeString("date")
                ?: ""

            val rawTier = doc.getSafeString("planTier") ?: doc.getSafeString("packageTier") ?: doc.getSafeString("tier")
            val tier = if (rawTier.isNullOrBlank()) "غير محدد" else rawTier.trim()

            val slugVal = doc.getSafeString("slug") ?: doc.id
            val titleVal = doc.getSafeString("title")
                ?: eventDetailsMap?.get("eventTitle") as? String
                ?: ""

            val rsvpVal = when (val rawRsvp = data["rsvpCount"] ?: data["guestCount"] ?: data["guests"]) {
                is Number -> rawRsvp.toLong()
                is String -> rawRsvp.toLongOrNull() ?: 0L
                else -> 0L
            }

            return Invitation(
                id = doc.id,
                slug = slugVal,
                groomName = groomNameVal,
                brideName = brideNameVal,
                title = titleVal,
                eventDate = dateVal,
                status = doc.getSafeString("status") ?: doc.getSafeString("invitationStatus") ?: "draft",
                createdAt = doc.getFlexTimestamp("createdAt") ?: doc.getFlexTimestamp("date"),
                expiresAt = doc.getFlexTimestamp("expiresAt"),
                hostCode = doc.getSafeString("hostCode") ?: doc.getSafeString("accessCode"),
                venueName = venueVal,
                packageTier = tier,
                orderId = doc.getSafeString("orderId") ?: "",
                rsvpCount = rsvpVal,
                hostPortalUrl = doc.getSafeString("hostPortalUrl") ?: com.example.util.WebsiteUrlProvider.getHostPortalUrl(slugVal)
            )
        }
    }
}

data class PublicConfig(
    val vodafoneCashNumber: String = "",
    val walletOwnerName: String = "",
    val tier1Price: Double = 0.0,
    val tier2Price: Double = 0.0,
    val tier3Price: Double = 0.0,
    val customerSupportWhatsApp: String = ""
)

data class AdminConfig(
    val maintenanceMode: Boolean = false,
    val autoNotificationEnabled: Boolean = true,
    val cloudflareWorkerUrl: String = com.example.util.AppConfig.defaultWorkerUrl,
    val websiteBaseUrl: String = com.example.util.WebsiteUrlProvider.DEFAULT_BASE_URL
)

data class Review(
    val id: String = "",
    val userName: String = "",
    val rating: Int = 5,
    val comment: String = "",
    val invitationSlug: String = "",
    val approved: Boolean = false,
    val createdAt: Timestamp? = null
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): Review? {
            val data = doc.data ?: return null
            val nameVal = doc.getSafeString("customerName")
                ?: doc.getSafeString("userName")
                ?: doc.getSafeString("name")
                ?: doc.getSafeString("clientName")
                ?: "عميل"
            val ratingVal = when (val rawRating = data["rating"]) {
                is Number -> rawRating.toInt()
                is String -> rawRating.toDoubleOrNull()?.toInt() ?: 5
                else -> 5
            }.coerceIn(1, 5)

            return Review(
                id = doc.id,
                userName = nameVal,
                rating = ratingVal,
                comment = doc.getSafeString("comment")
                    ?: doc.getSafeString("feedback")
                    ?: doc.getSafeString("text")
                    ?: "",
                invitationSlug = doc.getSafeString("invitationSlug") ?: doc.getSafeString("slug") ?: "",
                approved = doc.getBoolean("approved") ?: false,
                createdAt = doc.getFlexTimestamp("createdAt")
            )
        }
    }
}

data class AudioTrack(
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val duration: String = "",
    val url: String = "",
    val previewUrl: String = "",
    val coverUrl: String = "",
    val category: String = "",
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val createdAt: Timestamp? = null
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): AudioTrack? {
            val data = doc.data ?: return null
            val keyVal = doc.getSafeString("id") ?: doc.getSafeString("key") ?: doc.getSafeString("r2Key") ?: doc.id
            val titleVal = doc.getSafeString("title") ?: doc.getSafeString("name") ?: "مقطع موسيقي"
            val artistVal = doc.getSafeString("artist") ?: "FRIDA Royal Orchestra"
            val audioUrlVal = doc.getSafeString("audioUrl") ?: doc.getSafeString("url") ?: ""
            val previewUrlVal = doc.getSafeString("previewUrl") ?: ""
            val coverUrlVal = doc.getSafeString("coverUrl") ?: ""
            val categoryVal = doc.getSafeString("category") ?: "عام"
            val isActiveVal = doc.getBoolean("isActive") ?: true
            val isDefaultVal = doc.getBoolean("isDefault") ?: false

            val durationVal = when (val rawDur = data["duration"]) {
                is Number -> {
                    val sec = rawDur.toInt()
                    val m = sec / 60
                    val s = sec % 60
                    String.format(java.util.Locale.US, "%02d:%02d", m, s)
                }
                is String -> rawDur
                else -> "02:30"
            }

            return AudioTrack(
                key = keyVal,
                title = titleVal,
                artist = artistVal,
                duration = durationVal,
                url = audioUrlVal,
                previewUrl = previewUrlVal,
                coverUrl = coverUrlVal,
                category = categoryVal,
                isActive = isActiveVal,
                isDefault = isDefaultVal,
                createdAt = doc.getFlexTimestamp("createdAt")
            )
        }
    }
}

data class CustomTemplate(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val previewImageUrl: String = "",
    val category: String = "wedding",
    val isActive: Boolean = true,
    val createdAt: Timestamp? = null
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): CustomTemplate? {
            val data = doc.data ?: return null
            return CustomTemplate(
                id = doc.id,
                name = doc.getSafeString("name") ?: doc.getSafeString("title") ?: "قالب مخصص",
                description = doc.getSafeString("description") ?: "",
                previewImageUrl = doc.getSafeString("previewImageUrl") ?: doc.getSafeString("thumbnail") ?: doc.getSafeString("imageUrl") ?: "",
                category = doc.getSafeString("category") ?: "wedding",
                isActive = doc.getBoolean("isActive") ?: true,
                createdAt = doc.getFlexTimestamp("createdAt")
            )
        }
    }
}

data class DashboardStats(
    val pendingOrdersCount: Long = 0,
    val publishedInvitationsCount: Long = 0,
    val totalApprovedRevenue: Double = 0.0,
    val totalVisitorsCount: Long = 0,
    val recentOrders: List<Order> = emptyList()
)
