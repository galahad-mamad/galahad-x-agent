package com.agent.app.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

/**
 * Screen Reader - reads the current screen using Accessibility Tree
 * Much more efficient than screenshot+OCR: no pixel capture, just DOM
 */
class ScreenReader(private val service: AccessibilityService) {

    /**
     * Get a text representation of the current screen
     */
    fun getScreenContent(): String {
        val root = service.rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        traverseNode(root, builder, 0)
        return builder.toString()
    }

    /**
     * Get interactive elements (buttons, inputs, links)
     */
    fun getInteractiveElements(): List<UIElement> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UIElement>()
        traverseInteractive(root, elements)
        return elements
    }

    /**
     * Find element by text
     */
    fun findElementByText(text: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByText(root, text)
    }

    /**
     * Find element by resource ID
     */
    fun findElementById(id: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeById(root, id)
    }

    /**
     * Find element by content description
     */
    fun findElementByDescription(description: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByDescription(root, description)
    }

    /**
     * Click on element by text
     */
    fun clickByText(text: String): Boolean {
        val element = findElementByText(text) ?: return false
        // climb to a clickable parent (usually the parent, not the text node itself)
        var target: AccessibilityNodeInfo? = element
        var hops = 0
        while (target != null && !target.isClickable && hops < 8) {
            target = target.parent
            hops++
        }
        if (target != null && target.isClickable) {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return true
        }
        // final attempt on the node itself + its direct parent
        if (element.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return element.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /**
     * Click on element by ID
     */
    fun clickById(id: String): Boolean {
        val element = findElementById(id) ?: return false
        return performClickOnNode(element)
    }

    /**
     * Type text into focused element
     */
    fun typeText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val focusedNode = findFocusedNode(root) ?: return false
        return performTypeOnNode(focusedNode, text)
    }

    /**
     * Type text into element by ID
     */
    fun typeIntoElement(id: String, text: String): Boolean {
        val element = findElementById(id) ?: return false
        return performTypeOnNode(element, text)
    }

    /**
     * Scroll down
     */
    fun scrollDown(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: root
        return performScrollOnNode(scrollable, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scroll up
     */
    fun scrollUp(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: root
        return performScrollOnNode(scrollable, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.childCount > 0) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findScrollableNode(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Press back button
     */
    fun pressBack(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Press home button
     */
    fun pressHome(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Press recent apps
     */
    fun pressRecents(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open notification shade
     */
    fun openNotifications(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Open quick settings
     */
    fun openQuickSettings(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        
        node.text?.let { 
            if (it.isNotBlank()) {
                builder.append("$indent$it\n")
            }
        }
        
        node.contentDescription?.let {
            if (it.isNotBlank()) {
                builder.append("$indent[$it]\n")
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, builder, depth + 1)
            }
        }
    }

    private fun traverseInteractive(node: AccessibilityNodeInfo, elements: MutableList<UIElement>) {
        val isClickable = node.isClickable
        val isFocusable = node.isFocusable
        val isScrollable = node.isScrollable
        
        if (isClickable || isFocusable || isScrollable) {
            val element = UIElement(
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                className = node.className?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                isClickable = isClickable,
                isFocusable = isFocusable,
                isScrollable = isScrollable
            )
            elements.add(element)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseInteractive(child, elements)
            }
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text, ignoreCase = true) == true) {
            return node
        }
        if (node.contentDescription?.contains(text, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByText(child, text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(id, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeById(child, id)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.contains(description, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByDescription(child, description)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findFocusedNode(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun performTypeOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        return node.performAction(action)
    }
}

data class UIElement(
    val text: String,
    val contentDescription: String,
    val className: String,
    val resourceId: String,
    val isClickable: Boolean,
    val isFocusable: Boolean,
    val isScrollable: Boolean
)
