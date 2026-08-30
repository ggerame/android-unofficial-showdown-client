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
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogRegisterAccountBinding
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.util.SimpleTextWatcher
import com.majeur.psclient.util.dp
import com.majeur.psclient.util.resizeForIme
import kotlinx.coroutines.launch
import kotlin.math.min

class RegisterAccountDialog : DialogFragment(), ShowdownService.AttemptRegistrationCallback {

    private var service: ShowdownService? = null
    private var submitting = false
    private var pendingError: String? = null

    private var _binding: DialogRegisterAccountBinding? = null
    private val binding get() = _binding!!
    private val username get() = requireArguments().getString(ARG_USERNAME).orEmpty()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        service = (context as MainActivity).service
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = DialogRegisterAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.username.setText(username)
        binding.password.requestFocus()

        val watcher = object : SimpleTextWatcher() {
            override fun afterTextChanged(editable: Editable) {
                clearErrors()
                updateSubmitButton()
            }
        }
        binding.password.addTextChangedListener(watcher)
        binding.confirmPassword.addTextChangedListener(watcher)
        binding.captcha.addTextChangedListener(watcher)
        binding.captcha.setOnEditorActionListener(
                TextView.OnEditorActionListener { _, actionId, _: KeyEvent? ->
                    if (actionId == EditorInfo.IME_ACTION_GO && binding.registerButton.isEnabled) {
                        submit()
                        true
                    } else false
                })
        binding.registerButton.setOnClickListener { submit() }
        binding.cancelButton.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            val icon = (requireActivity() as MainActivity).assetLoader.dexIcon("pikachu")
            if (_binding != null) binding.pokemonImage.setImageBitmap(icon)
        }

        setFormEnabled(!submitting)
        pendingError?.let(::showServerError)
    }

    override fun onStart() {
        super.onStart()
        requireDialog().resizeForIme(showKeyboard = true)
        val availableWidth = dp((resources.configuration.screenWidthDp - 32).toFloat())
        requireDialog().window?.setLayout(
                min(availableWidth, resources.getDimensionPixelSize(R.dimen.dialog_max_width)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        service = null
    }

    private fun submit() {
        if (submitting) return
        val password = binding.password.text?.toString().orEmpty()
        val confirmation = binding.confirmPassword.text?.toString().orEmpty()
        val captcha = binding.captcha.text?.toString().orEmpty()
        when (validateRegistration(username, password, confirmation, captcha)) {
            RegistrationValidationError.REQUIRED_FIELDS -> showRequiredErrors()
            RegistrationValidationError.PASSWORD_TOO_SHORT ->
                binding.passwordContainer.error = getString(R.string.registration_password_too_short)
            RegistrationValidationError.PASSWORD_MISMATCH ->
                binding.confirmPasswordContainer.error = getString(R.string.registration_password_mismatch)
            null -> {
                submitting = true
                pendingError = null
                setFormEnabled(false)
                service?.attemptRegistration(username, password, captcha, this)
                        ?: onError(getString(R.string.registration_session_unavailable))
            }
        }
    }

    private fun showRequiredErrors() {
        val required = getString(R.string.required_field)
        if (username.isBlank()) binding.usernameContainer.error = required
        if (binding.password.text.isNullOrBlank()) binding.passwordContainer.error = required
        if (binding.confirmPassword.text.isNullOrBlank()) binding.confirmPasswordContainer.error = required
        if (binding.captcha.text.isNullOrBlank()) binding.captchaContainer.error = required
    }

    private fun clearErrors() {
        pendingError = null
        binding.usernameContainer.isErrorEnabled = false
        binding.passwordContainer.isErrorEnabled = false
        binding.confirmPasswordContainer.isErrorEnabled = false
        binding.captchaContainer.isErrorEnabled = false
        binding.registrationError.visibility = View.GONE
    }

    private fun setFormEnabled(enabled: Boolean) {
        binding.password.isEnabled = enabled
        binding.confirmPassword.isEnabled = enabled
        binding.captcha.isEnabled = enabled
        binding.cancelButton.isEnabled = enabled
        isCancelable = enabled
        if (enabled) {
            binding.registerButton.setText(R.string.register)
            updateSubmitButton()
        } else {
            binding.registerButton.setText(R.string.loading)
            binding.registerButton.isEnabled = false
        }
    }

    private fun updateSubmitButton() {
        binding.registerButton.isEnabled = !submitting &&
                !binding.password.text.isNullOrBlank() &&
                !binding.confirmPassword.text.isNullOrBlank() &&
                !binding.captcha.text.isNullOrBlank()
    }

    private fun showServerError(message: String) {
        pendingError = message
        binding.registrationError.text = message
        binding.registrationError.visibility = View.VISIBLE
    }

    override fun onSuccess() {
        submitting = false
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
        dismissAllowingStateLoss()
    }

    override fun onError(reason: String) {
        submitting = false
        pendingError = reason
        if (_binding == null) return
        setFormEnabled(true)
        showServerError(reason)
    }

    companion object {
        const val TAG = "register-account-dialog"
        const val RESULT_KEY = "register-account-result"
        private const val ARG_USERNAME = "username"

        fun newInstance(username: String) = RegisterAccountDialog().apply {
            arguments = Bundle().apply { putString(ARG_USERNAME, username) }
        }
    }
}

internal enum class RegistrationValidationError {
    REQUIRED_FIELDS,
    PASSWORD_TOO_SHORT,
    PASSWORD_MISMATCH
}

internal fun validateRegistration(
        username: String,
        password: String,
        confirmation: String,
        captcha: String
): RegistrationValidationError? = when {
    username.isBlank() || password.isBlank() || confirmation.isBlank() || captcha.isBlank() ->
        RegistrationValidationError.REQUIRED_FIELDS
    password.count { !it.isWhitespace() } < 5 -> RegistrationValidationError.PASSWORD_TOO_SHORT
    password != confirmation -> RegistrationValidationError.PASSWORD_MISMATCH
    else -> null
}
