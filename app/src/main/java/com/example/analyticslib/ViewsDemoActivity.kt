package com.example.analyticslib

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.analytics.tracking.ScreenVisitTracker
import com.example.analytics.views.setTrackedOnClickListener
import com.example.analytics.views.trackImpressions
import com.example.analytics.views.trackScreen

/** The same product list built with Views + RecyclerView, tracked by `analytics-views`. */
class ViewsDemoActivity : ComponentActivity() {

    private lateinit var tracker: ScreenVisitTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ViewsDemoActivity)
            adapter = ProductAdapter()
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Views + RecyclerView demo"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(16), dp(16), dp(16), dp(8))
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        tracker = trackScreen("product_list_views", this, this, window.decorView, properties = {
            mapOf("item_count" to mockProducts.size)
        })
        list.trackImpressions(
            tracker,
            itemKey = { mockProducts[it].id },
            itemName = { "product_impression" },
            itemProperties = { mapOf("position" to it, "category" to mockProducts[it].category) },
        )
    }

    private inner class ProductAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = mockProducts.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
                    setMargins(dp(16), dp(4), dp(16), dp(4))
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setBackgroundColor(Color.rgb(0xE3, 0xE3, 0xE8))
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val product = mockProducts[position]
            (holder.itemView as TextView).text = "${product.title}  ·  ${product.category}  ·  ₹${product.price.toInt()}"
            holder.itemView.setTrackedOnClickListener(
                tracker,
                "product_click",
                key = product.id,
                properties = { mapOf("position" to position, "category" to product.category) },
            ) {
                Toast.makeText(this@ViewsDemoActivity, "Clicked ${product.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()
}
