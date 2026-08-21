package com.majeur.psclient.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.fragment.app.DialogFragment
import com.majeur.psclient.databinding.DialogSignInBinding
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.service.ShowdownService.AttemptSignInCallback
import com.majeur.psclient.util.SimpleTextWatcher
import com.majeur.psclient.util.resizeForIme

class SignInDialog : DialogFragment(), View.OnClickListener, AttemptSignInCallback {

    private var service: ShowdownService? = null
    private var requirePassword = false

    private var _binding: DialogSignInBinding? = null
    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        super.onAttach(context)
        service = (context as MainActivity).service
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = DialogSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            username.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(editable: Editable) {
                    val invalid = NAME_REGEX.containsMatchIn(editable)
                    if (invalid)
                        usernameContainer.error = "| , ; are not valid characters in names"
                    else usernameContainer.isErrorEnabled = false
                    if (!requirePassword) button.isEnabled = editable.isNotBlank() && !invalid
                }
            })
            password.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(editable: Editable) {
                    if (requirePassword) button.isEnabled = editable.isNotBlank()
                }
            })
            username.requestFocus()
            // username.setFilters(new InputFilter[] {new UserNameFilter(), new InputFilter.LengthFilter(18)});
            username.setOnEditorActionListener(mEnterPressListener)
            password.setOnEditorActionListener(mEnterPressListener)
            button.setOnClickListener(this@SignInDialog)
            cancelButton.setOnClickListener { dismiss() }
        }
    }

    override fun onStart() {
        super.onStart()
        requireDialog().resizeForIme(showKeyboard = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        service = null
    }

    override fun onClick(view: View) {
        if (!binding.button.isEnabled) return
        binding.cancelButton.isEnabled = false
        if (requirePassword) {
            if (!binding.password.text.isNullOrEmpty()) {
                binding.password.isEnabled = false
                binding.button.text = getString(com.majeur.psclient.R.string.loading)
                binding.button.isEnabled = false
                isCancelable = false
                service?.attemptSignIn(binding.username.text.toString(),
                        binding.password.text.toString(), this@SignInDialog)
            }
        } else {
            if (!binding.username.text.isNullOrEmpty()) {
                binding.username.isEnabled = false
                binding.button.text = getString(com.majeur.psclient.R.string.loading)
                binding.button.isEnabled = false
                isCancelable = false
                service!!.attemptSignIn(binding.username.text.toString(), this@SignInDialog)
            }
        }
    }

    override fun onSuccess() {
        if (_binding == null) return
        isCancelable = true
        binding.usernameContainer.isErrorEnabled = false
        binding.passwordContainer.isErrorEnabled = false
        requireDialog().dismiss()
    }

    override fun onAuthenticationRequired() {
        if (_binding == null) return
        isCancelable = true
        requirePassword = true
        binding.apply {
            title.setText(com.majeur.psclient.R.string.password_required)
            description.setText(com.majeur.psclient.R.string.password_required_description)
            passwordContainer.visibility = View.VISIBLE
            password.isEnabled = true
            password.requestFocus()
            button.setText(com.majeur.psclient.R.string.sign_in)
            button.isEnabled = !password.text.isNullOrBlank()
            cancelButton.isEnabled = true
            usernameContainer.isErrorEnabled = false // If any issue happened before
        }
    }

    override fun onError(reason: String) {
        if (_binding == null) return
        isCancelable = true
        if (requirePassword) {
            binding.password.isEnabled = true
            binding.passwordContainer.error = reason
            binding.button.setText(com.majeur.psclient.R.string.sign_in)
            binding.button.isEnabled = !binding.password.text.isNullOrBlank()
            binding.cancelButton.isEnabled = true
        } else {
            binding.usernameContainer.error = reason
            binding.username.isEnabled = true
            binding.button.setText(com.majeur.psclient.R.string.continue_label)
            binding.button.isEnabled = !binding.username.text.isNullOrBlank()
            binding.cancelButton.isEnabled = true
        }
    }

    private val mEnterPressListener = OnEditorActionListener { v: TextView, actionId: Int, _: KeyEvent? ->
        if (actionId == EditorInfo.IME_ACTION_GO) {
            onClick(v)
            return@OnEditorActionListener true
        }
        false
    }

    companion object {
        val NAME_REGEX = "[|,;]".toRegex()
        const val FRAGMENT_TAG = "sign-in-dialog"
    }
}
