package com.majeur.psclient.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.majeur.psclient.R
import com.majeur.psclient.model.battle.Player
import com.majeur.psclient.model.pokemon.BasePokemon
import com.majeur.psclient.model.pokemon.BattlingPokemon
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.glide.AnimatedImageViewTarget
import com.majeur.psclient.util.html.Html
import com.majeur.psclient.util.minusFirst
import com.majeur.psclient.widget.BattleLayout
import timber.log.Timber
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt

class GlideHelper(context: Context) {

    enum class SpriteType(private val path: String, private val ext: String) {

        D3ANIMATED("ani", "gif"), // Gen 6+ 3D animated
        D2ANIMATED("gen5ani", "gif"), // Gen 5 2D animated
        D2("gen5", "png"), // Gen 5 2D non animated
        DEX("dex", "png"),
        HOME("home", "png"), // Pokémon HOME artwork (has correct shiny variants for all gens)
        TRAINER("trainers", "png"); // Dex

        fun uri(spriteId: String, shiny: Boolean, back: Boolean): Uri = Uri.Builder().run {
            scheme("https")
            authority("play.pokemonshowdown.com")
            appendPath("sprites")
            var dir = path
            if (back) dir += "-back"
            if (shiny) dir += "-shiny"
            appendPath(dir)
            appendPath("$spriteId.$ext")
            build()
        }
    }

    companion object {
        private const val MAGIC_SCALE = 0.0027777777777778f

        // Same backdrops the web client picks from for gen 6+ formats (sprites/gen6bgs/).
        private val BATTLE_BACKDROPS = arrayOf(
                "bg-aquacordtown.jpg", "bg-beach.jpg", "bg-city.jpg", "bg-dampcave.jpg",
                "bg-darkbeach.jpg", "bg-darkcity.jpg", "bg-darkmeadow.jpg", "bg-deepsea.jpg",
                "bg-desert.jpg", "bg-earthycave.jpg", "bg-elite4drake.jpg", "bg-forest.jpg",
                "bg-icecave.jpg", "bg-leaderwallace.jpg", "bg-library.jpg", "bg-meadow.jpg",
                "bg-orasdesert.jpg", "bg-orassea.jpg", "bg-skypillar.jpg")

        // Mirrors the web client: the backdrop is derived from the numeric battle id so that
        // both players (and spectators) see the same background for a given battle.
        private fun battleBackdropFor(roomId: String?): String {
            val n = roomId?.substringAfterLast('-')?.toIntOrNull()
            val index = if (n != null && n != 0)
                ((n % BATTLE_BACKDROPS.size) + BATTLE_BACKDROPS.size) % BATTLE_BACKDROPS.size
            else (Math.random() * BATTLE_BACKDROPS.size).toInt()
            return BATTLE_BACKDROPS[index]
        }
    }

    private val glide = Glide.with(context)
    private val resources = context.resources

    fun loadBattleBackground(roomId: String?, imageView: ImageView) {
        val uri = Uri.Builder().scheme("https").authority("play.pokemonshowdown.com")
                .appendPath("sprites").appendPath("gen6bgs").appendPath(battleBackdropFor(roomId)).build()
        glide.load(uri)
                .apply(RequestOptions().centerCrop().error(R.drawable.battle_bg_1))
                .into(imageView)
    }

    fun loadFieldFxBitmap(fileName: String, callback: (Bitmap) -> Unit) {
        val uri = Uri.Builder().scheme("https").authority("play.pokemonshowdown.com")
                .appendPath("fx").appendPath(fileName).build()
        glide.asBitmap().load(uri).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = callback(resource)
            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    fun loadBattleSprite(pokemon: BattlingPokemon, imageView: ImageView) {
        val spriteId = pokemon.transformSpecies ?: pokemon.spriteId
        // When the battle view is flipped the two sides swap places: the near (bottom) side shows
        // its back to the viewer and is drawn larger, regardless of which logical player it is.
        val flipped = (imageView.parent as? BattleLayout)?.flipped ?: false
        val nearSide = pokemon.trainer != flipped
        loadSprite(spriteId, nearSide, pokemon.shiny, true,
                SpriteType.D3ANIMATED, SpriteType.D2ANIMATED, SpriteType.D2)
            .into(object : AnimatedImageViewTarget(imageView) {

            override fun onInitInAnimation(viewPropertyAnimator: ViewPropertyAnimator) {
                viewPropertyAnimator
                        .setDuration(250)
                        .setInterpolator(DecelerateInterpolator())
                        .scaleX(0f)
                        .scaleY(0f)
                        .alpha(0f)
            }

            override fun onInitOutAnimation(viewPropertyAnimator: ViewPropertyAnimator) {
                viewPropertyAnimator
                        .setDuration(250)
                        .setInterpolator(AccelerateInterpolator())
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
            }

                override fun onApplyResourceSize(w: Int, h: Int) {
                val battleLayout = imageView.parent as BattleLayout
                var scale = battleLayout.width * MAGIC_SCALE
                if (pokemon.trainer != battleLayout.flipped) scale *= 1.5f
                getView().layoutParams.apply {
                    width = (w * scale).roundToInt()
                    height = (h * scale).roundToInt()
                }
            }
        })
    }

    fun loadPreviewSprite(player: Player, pokemon: BasePokemon, imageView: ImageView) {
        val flipped = (imageView.parent as? BattleLayout)?.flipped ?: false
        loadSprite(pokemon.spriteId, (player == Player.TRAINER) != flipped, false, true,
                SpriteType.D3ANIMATED, SpriteType.D2ANIMATED, SpriteType.D2)
            .into(object : AnimatedImageViewTarget(imageView) {
                override fun onInitInAnimation(viewPropertyAnimator: ViewPropertyAnimator) = Unit
                override fun onInitOutAnimation(viewPropertyAnimator: ViewPropertyAnimator) = Unit

                override fun onApplyResourceSize(w: Int, h: Int) {
                    val fieldWidth = (imageView.parent as BattleLayout?)?.width ?: 0
                    val scale = fieldWidth * MAGIC_SCALE
                    imageView.layoutParams.apply {
                        width = (w * scale).roundToInt()
                        height = (h * scale).roundToInt()
                        Timber.d("resouce set")
                    }
                }
            }).also {
                imageView.setTag(R.id.glide_tag, it)
            }
    }

    fun loadDexSprite(pokemon: BasePokemon, shiny: Boolean, imageView: ImageView) {
        loadSprite(pokemon.spriteId, false, shiny, true, SpriteType.HOME, SpriteType.DEX, SpriteType.D2)
                .into(imageView)
    }

    fun loadAvatar(avatar: String, imageView: ImageView) {
        loadSprite(avatar, false, false, false, SpriteType.TRAINER)
                .into(imageView)
    }

    // Load sprite trying with each sprite type if previous has failed
    private fun loadSprite(spriteId: String, back: Boolean, shiny: Boolean,
                           overrideSize: Boolean, vararg spriteTypes: SpriteType): RequestBuilder<Drawable> {
        val options = RequestOptions().apply {
            if (overrideSize) override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            if (spriteTypes.size == 1) error(R.drawable.missingno) // No more sprite types, default fallback
        }
        return glide.load(spriteTypes.first().uri(spriteId, shiny, back)).apply {
            apply(options)
            if (spriteTypes.size > 1) // There is more sprite types, add an error fallback
                error(loadSprite(spriteId, back, shiny, overrideSize, *spriteTypes.minusFirst()))
        }
    }

    fun getHtmlImageGetter(iconLoader: AssetLoader, maxWidth: Int): Html.ImageGetter {
        val mw = maxWidth - Utils.dpToPx(2f)
        return Html.ImageGetter { source, reqw, reqh ->
            try {
                var d: Drawable? = null
                if (source.startsWith("content://com.majeur.psclient/dex-icon/")) {
                    val species = source.substring(source.lastIndexOf('/') + 1, source.length)
                    val icon = iconLoader.dexIconNonSuspend(species)
                    if (icon != null) d = BitmapDrawable(resources, icon)
                } else {
                    d = glide.asDrawable().load(source).submit().get()
                }
                if (d == null) return@ImageGetter null
                val r = d.intrinsicWidth / d.intrinsicHeight.toFloat()
                var w: Int
                var h: Int
                if (reqw != 0 && reqh == 0) {
                    w = reqw
                    h = (w / r).toInt()
                } else if (reqw == 0 && reqh != 0) {
                    h = reqh
                    w = (h * r).toInt()
                } else {
                    w = reqw
                    h = reqh
                }
                val mr = w / mw.toFloat()
                if (mr > 1) {
                    w = mw
                    h /= mr.toInt()
                }
                d.setBounds(0, 0, w, h)
                return@ImageGetter d
            } catch (e: ExecutionException) {
                e.printStackTrace()
                return@ImageGetter null
            } catch (e: InterruptedException) {
                e.printStackTrace()
                return@ImageGetter null
            }
        }
    }
}